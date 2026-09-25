package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.RejectReason;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
import com.github.lisama569.lakehousetableformatjava.storage.LocalCatalog;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteValidationTest {

    private static final Logger LOG = LoggerFactory.getLogger(WriteValidationTest.class);

    @TempDir
    Path warehouse;
    private LocalCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new LocalCatalog(warehouse);
        catalog.createTable("orders",
                List.of(
                        new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("name", DataType.STRING, true, null),
                        new ColumnSpec("amount", DataType.DOUBLE, true, null),
                        new ColumnSpec("active", DataType.BOOLEAN, true, null)),
                List.of("id"));
    }

    private WriteResult write(WriteMode mode, Long expected, Map<String, Object>... rows) {
        return catalog.openTable("orders")
                .commit(new WriteRequest(mode, expected, List.of(rows)));
    }

    @Test
    void appendThenMergeUpdatesDeterministically() {
        WriteResult r1 = write(WriteMode.APPEND, 0L,
                Map.of("id", 1, "name", "a", "amount", 10.0),
                Map.of("id", 2, "name", "b", "amount", 20.0));
        assertTrue(r1.committed());
        assertEquals(1L, r1.committedVersion());
        LOG.info("[write] append committed version={} snapshotId={} basis=base v0",
                r1.committedVersion(), r1.snapshotId());

        // MERGE 更新 id=1 并新增 id=3
        WriteResult r2 = write(WriteMode.MERGE, 1L,
                Map.of("id", 1, "name", "a2", "amount", 11.0),
                Map.of("id", 3, "name", "c", "amount", 30.0));
        assertTrue(r2.committed());
        assertEquals(2L, r2.committedVersion());
        Table t = catalog.openTable("orders");
        List<Map<String, Object>> rows = t.readLatest();
        assertEquals(3, rows.size());
        Map<String, Object> updated = rows.stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow();
        assertEquals("a2", updated.get("name"));
        assertEquals(11.0d, updated.get("amount"));
        LOG.info("[write] merge committed version={} snapshotId={} basis=base v1; id=1 name now a2",
                r2.committedVersion(), r2.snapshotId());
    }

    @Test
    void appendRejectsExistingPrimaryKey() {
        write(WriteMode.APPEND, 0L, Map.of("id", 1, "name", "a"));
        WriteResult r = write(WriteMode.APPEND, 1L, Map.of("id", 1, "name", "b"));
        assertFalse(r.committed());
        assertEquals(RejectReason.PRIMARY_KEY_ALREADY_EXISTS, r.rejected().get(0).reason());
        LOG.warn("[write:reject] reason={} msg={}", r.rejected().get(0).reason(), r.rejected().get(0).message());
    }

    @Test
    void rejectsDistinctReasons() {
        // 缺主键
        WriteResult noPk = write(WriteMode.APPEND, 0L, Map.of("name", "x"));
        assertEquals(RejectReason.MISSING_PRIMARY_KEY, noPk.rejected().get(0).reason());
        // 类型不匹配
        WriteResult badType = write(WriteMode.APPEND, 0L,
                Map.of("id", 1, "amount", "not-a-number"));
        assertEquals(RejectReason.TYPE_MISMATCH, badType.rejected().get(0).reason());
        // 未知列
        WriteResult unknown = write(WriteMode.APPEND, 0L,
                Map.of("id", 1, "ghost", "x"));
        assertEquals(RejectReason.UNKNOWN_COLUMN, unknown.rejected().get(0).reason());
        // 批内重复主键
        WriteResult dup = write(WriteMode.APPEND, 0L,
                Map.of("id", 1, "name", "first"),
                Map.of("id", 1, "name", "second"));
        assertEquals(RejectReason.DUPLICATE_PRIMARY_KEY_IN_BATCH, dup.rejected().get(0).reason());
        assertEquals(1, dup.rejected().get(0).batchIndex());
        LOG.warn("[write:reject] distinct reasons verified: MISSING_PRIMARY_KEY / TYPE_MISMATCH / UNKNOWN_COLUMN / DUPLICATE_PRIMARY_KEY_IN_BATCH");
    }

    @Test
    void nonNullableColumnWithoutDefaultIsRejected() {
        catalog.createTable("strict",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("note", DataType.STRING, false, null)),
                List.of("id"));
        WriteResult r = catalog.openTable("strict").commit(new WriteRequest(
                WriteMode.APPEND, 0L, List.of(Map.of("id", 1))));
        assertFalse(r.committed());
        assertEquals(RejectReason.MISSING_REQUIRED_COLUMN, r.rejected().get(0).reason());
        LOG.warn("[write:reject] reason=MISSING_REQUIRED_COLUMN msg={}", r.rejected().get(0).message());
    }

    @Test
    void rejectedBatchIsAllOrNothing() {
        WriteResult r = write(WriteMode.APPEND, 0L,
                Map.of("id", 1, "name", "ok"),
                Map.of("name", "missing-pk"),
                Map.of("id", 2, "amount", "bad-type"));
        assertFalse(r.committed());
        assertEquals(2, r.rejected().size(), "both bad records reported, none silently dropped");
        // 表仍为空，未产生部分写入
        assertEquals(0, catalog.openTable("orders").readLatest().size());
        assertEquals(0L, catalog.openTable("orders").currentSnapshot().version());
        LOG.warn("[write:reject] all-or-nothing: rejectedCount={} headVersion unchanged=0", r.rejected().size());
    }
}
