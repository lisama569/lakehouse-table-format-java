package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.storage.LocalCatalog;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotIsolationTest {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotIsolationTest.class);

    @TempDir
    Path warehouse;

    @Test
    void everyCommittedSnapshotIsReproducibleAfterReopen() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");

        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"))));
        t.commit(new WriteRequest(WriteMode.MERGE, 1L,
                List.of(Map.of("id", 1, "v", "b"), Map.of("id", 2, "v", "c"))));

        // 用一个全新 catalog（仅从磁盘恢复）复现每个版本
        LocalCatalog reopened = new LocalCatalog(warehouse);
        Table t2 = reopened.openTable("t");

        assertEquals(0, t2.readAt(0).size(), "v0 empty table");
        assertEquals(1, t2.readAt(1).size());
        assertEquals("a", t2.readAt(1).get(0).get("v"));
        assertEquals(2, t2.readAt(2).size());
        assertEquals("b", t2.readAt(2).stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
        assertEquals(2, t2.readLatest().size());

        List<Snapshot> history = t2.history();
        assertEquals(3, history.size());
        for (Snapshot s : history) {
            assertNotNull(s.snapshotId());
            LOG.info("[snapshot] version={} snapshotId={} parent={} operation={} files={} rows={}",
                    s.version(), s.snapshotId(), s.parentVersion(), s.operation(),
                    s.files().size(), t2.readRows(s).size());
        }
    }

    @Test
    void staleSnapshotWriteIsRejectedNotOverwriting() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null)),
                List.of("id"));
        Table t = catalog.openTable("t");

        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1)))); // v1
        // 另一个提交者基于 v0 追加 id=2，但头已到 v1
        WriteRequest stale = new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 2)));
        ConflictException ex = assertThrows(ConflictException.class, () -> t.commit(stale));
        assertEquals(0L, ex.expectedVersion());
        assertEquals(1L, ex.currentVersion());
        // 过期写入没有覆盖：头仍是 v1，数据仍只有 id=1
        assertEquals(1L, t.currentSnapshot().version());
        assertEquals(1, t.readLatest().size());
        LOG.warn("[conflict:reject] stale commit expectedVersion=0 currentVersion=1 snapshotId={} basis=OCC head mismatch; no overwrite",
                t.currentSnapshot().snapshotId());

        // 基于最新版本重试可以成功 -> v2
        t.commit(new WriteRequest(WriteMode.APPEND, 1L, List.of(Map.of("id", 2))));
        assertEquals(2L, t.currentSnapshot().version());
        assertEquals(2, t.readLatest().size());
        LOG.info("[conflict:retry] baseVersion=1 committedVersion=2 snapshotId={} basis=refreshed snapshot",
                t.currentSnapshot().snapshotId());
    }

    @Test
    void schemaEvolutionAlsoGuardedByOcc() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null)),
                List.of("id"));
        Table t = catalog.openTable("t");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1)))); // v1
        assertThrows(ConflictException.class, () -> t.evolve(
                new com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest(
                        0L,
                        List.of(new com.github.lisama569.lakehousetableformatjava.model.ColumnChange(
                                com.github.lisama569.lakehousetableformatjava.model.ColumnChange.Kind.ADD,
                                "c1", null,
                                new ColumnSpec("c1", DataType.STRING, true, null))))));
        LOG.warn("[evolve:reject] schema change on stale version=0 while head=1 basis=OCC");
    }
}
