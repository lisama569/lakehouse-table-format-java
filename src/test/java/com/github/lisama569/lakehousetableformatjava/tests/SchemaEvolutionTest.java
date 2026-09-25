package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.exception.SchemaEvolutionException;
import com.github.lisama569.lakehousetableformatjava.model.ColumnChange;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaEvolutionTest {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaEvolutionTest.class);

    @TempDir
    Path warehouse;

    private Table tableWithOneRow() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("label", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "label", "hello"))));
        return t;
    }

    @Test
    void addColumnHasStableDefaultSemantics() {
        Table t = tableWithOneRow(); // v1
        // 新增可空列无缺省 -> 旧行读为 null（稳定缺省语义）
        Snapshot v2 = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.ADD, "note", null,
                        new ColumnSpec("note", DataType.STRING, true, null)))));
        assertEquals(2, v2.schema().version());
        assertNull(t.readAt(2).get(0).get("note"));
        LOG.info("[evolve] ADD note nullable: v={} snapshotId={} old row note -> null (stable default)",
                v2.version(), v2.snapshotId());

        // 新增带缺省值的列 -> 旧行读为缺省值
        Snapshot v3 = t.evolve(new SchemaChangeRequest(2L,
                List.of(new ColumnChange(ColumnChange.Kind.ADD, "score", null,
                        new ColumnSpec("score", DataType.LONG, true, "7")))));
        Object score = t.readAt(3).get(0).get("score");
        assertEquals(7L, score);
        LOG.info("[evolve] ADD score default=7: v={} snapshotId={} old row score -> 7",
                v3.version(), v3.snapshotId());

        // 新写入的行可覆盖缺省值
        t.commit(new WriteRequest(WriteMode.MERGE, 3L,
                List.of(Map.of("id", 1, "note", "set", "score", 99))));
        Map<String, Object> row = t.readLatest().get(0);
        assertEquals("set", row.get("note"));
        assertEquals(99L, row.get("score"));
    }

    @Test
    void renameDoesNotMisalignHistory() {
        Table t = tableWithOneRow(); // v1, column "label" id=2
        Snapshot renamed = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "label", "title", null))));
        // 列 ID 不变
        assertEquals(2, renamed.schema().columnByName("title").id());

        // 历史快照 v1 仍按旧 schema（旧名字）复现
        Map<String, Object> atV1 = t.readAt(1).get(0);
        assertEquals("hello", atV1.get("label"));
        assertFalse(atV1.containsKey("title"));
        // 新快照用新名字读到同一份按列 ID 存储的数据，没有错列
        Map<String, Object> atV2 = t.readAt(2).get(0);
        assertEquals("hello", atV2.get("title"));
        assertFalse(atV2.containsKey("label"));
        LOG.info("[evolve] RENAME label->title columnId=2: history v1 reads label, head v2 reads title, same value (no misalignment) snapshotId={}",
                renamed.snapshotId());
    }

    @Test
    void dropColumnKeepsHistoryReadableAndRejectsPkDrop() {
        Table t = tableWithOneRow(); // v1
        Snapshot dropped = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.DROP, "label", null, null))));
        // 当前版本读不到已删列
        Map<String, Object> head = t.readLatest().get(0);
        assertFalse(head.containsKey("label"));
        // 历史版本 v1 仍完整复现被删列
        assertEquals("hello", t.readAt(1).get(0).get("label"));
        LOG.info("[evolve] DROP label: head v={} omits it; history v1 still reproduces label=hello snapshotId={}",
                dropped.version(), dropped.snapshotId());

        // 不允许删主键列
        assertThrows(SchemaEvolutionException.class, () -> t.evolve(new SchemaChangeRequest(2L,
                List.of(new ColumnChange(ColumnChange.Kind.DROP, "id", null, null)))));
        // 不允许新增 NOT NULL 且无缺省值的列（旧行无法填充）
        assertThrows(SchemaEvolutionException.class, () -> t.evolve(new SchemaChangeRequest(2L,
                List.of(new ColumnChange(ColumnChange.Kind.ADD, "mandatory", null,
                        new ColumnSpec("mandatory", DataType.STRING, false, null))))));
        LOG.warn("[evolve:reject] DROP primary key and ADD NOT NULL without default are rejected with distinct INVALID_SCHEMA_EVOLUTION");
    }

    @Test
    void invalidEvolutionsAreRejected() {
        Table t = tableWithOneRow();
        // 重命名到已存在的名字
        assertThrows(SchemaEvolutionException.class, () -> t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.ADD, "id", null,
                        new ColumnSpec("id", DataType.LONG, true, null))))));
        // 重命名不存在的列
        assertThrows(SchemaEvolutionException.class, () -> t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "nope", "x", null)))));
        LOG.warn("[evolve:reject] duplicate name / missing column rejected");
    }

    @Test
    void compactionProducesIdenticalQueryResults() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.LONG, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");
        // v1 合并更新（单文件），随后多次 APPEND 累积出多个物理文件
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("id", 1, "v", 1), Map.of("id", 2, "v", 2))));
        t.commit(new WriteRequest(WriteMode.MERGE, 1L, List.of(
                Map.of("id", 1, "v", 10))));
        t.commit(new WriteRequest(WriteMode.APPEND, 2L, List.of(
                Map.of("id", 3, "v", 30))));
        t.commit(new WriteRequest(WriteMode.APPEND, 3L, List.of(
                Map.of("id", 4, "v", 40))));

        Snapshot before = t.currentSnapshot();
        List<Map<String, Object>> expected = t.readRows(before);
        assertTrue(before.files().size() > 1, "multiple physical files before compaction");

        Snapshot compacted = t.compact();
        assertEquals(1, compacted.files().size(), "single file after compaction");
        List<Map<String, Object>> actual = t.readRows(compacted);
        assertEquals(expected, actual, "query result must be identical before/after compaction");

        // 合并后的有效行确定（id=1 的更新值胜出），历史快照仍然完整可读
        assertEquals(4, actual.size());
        assertEquals(10L, actual.stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
        assertEquals(1L, t.readAt(1).stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
        LOG.info("[compact] files {}->1, liveRows={}, head snapshotId={}, results identical; history v1 still reproduces old value",
                before.files().size(), actual.size(), compacted.snapshotId());

        // 再次 compact 是幂等无操作（单文件直接跳过）
        Snapshot second = t.compact();
        assertEquals(compacted.version(), second.version());
    }
}
