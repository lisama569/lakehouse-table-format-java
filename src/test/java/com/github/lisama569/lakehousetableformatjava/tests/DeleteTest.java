package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.ColumnChange;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RejectReason;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteTest {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteTest.class);

    @TempDir
    Path warehouse;

    private Table newTable(LocalCatalog catalog) {
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        return catalog.openTable("t");
    }

    @Test
    void deletedKeysDisappearFromCurrentButHistoryKeepsThem() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);

        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b"),
                        Map.of("id", 3, "v", "c")))); // v1
        WriteResult del = t.delete(new DeleteRequest(1L,
                List.of(Map.of("id", 1), Map.of("id", 3)))); // v2

        assertTrue(del.committed());
        assertEquals(2L, del.committedVersion());
        assertEquals(2, del.acceptedCount());

        List<Map<String, Object>> current = t.readLatest();
        assertEquals(1, current.size());
        assertEquals(2L, current.get(0).get("id"));

        // 删除之前的历史版本照旧可读
        List<Map<String, Object>> before = t.readAt(1);
        assertEquals(3, before.size());
        assertTrue(before.stream().anyMatch(m -> m.get("id").equals(1L)));
        assertTrue(before.stream().anyMatch(m -> m.get("id").equals(3L)));

        Snapshot s2 = t.currentSnapshot();
        LOG.info("[delete:visibility] committedVersion={} snapshotId={} currentRows={} historyV1Rows={} basis=tombstone(commitVersion=2)>data(commitVersion=1)",
                s2.version(), s2.snapshotId(), current.size(), before.size());
    }

    @Test
    void invalidDeleteBatchIsFullyRejectedWithDistinctReasons() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b")))); // v1

        WriteResult r = t.delete(new DeleteRequest(1L, List.of(
                Map.of("id", 1),          // 合法，但整批拒绝时不生效
                Map.of("id", 1),          // 批内重复
                Map.of("id", 99),         // 主键不存在
                Map.of("v", "no-pk")      // 缺少主键
        )));

        assertFalse(r.committed());
        assertEquals(3, r.rejected().size());
        assertEquals(RejectReason.DUPLICATE_PRIMARY_KEY_IN_BATCH, r.rejected().get(0).reason());
        assertEquals(1, r.rejected().get(0).batchIndex());
        assertEquals(RejectReason.PRIMARY_KEY_NOT_FOUND, r.rejected().get(1).reason());
        assertEquals(2, r.rejected().get(1).batchIndex());
        assertEquals(RejectReason.MISSING_PRIMARY_KEY, r.rejected().get(2).reason());
        assertEquals(3, r.rejected().get(2).batchIndex());

        // all-or-nothing：版本未推进，数据未变
        assertEquals(1L, t.currentSnapshot().version());
        assertEquals(2, t.readLatest().size());
        LOG.info("[delete:atomic] committed=false version stays=1 reasons={} basis=all-or-nothing validation",
                r.rejected().stream().map(x -> x.reason().name()).toList());
    }

    @Test
    void rewriteAfterDeleteIsDeterministic() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1, "v", "old")))); // v1
        t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1)))); // v2
        assertEquals(0, t.readLatest().size());

        // 明确规则：删除只作用于其基版本之前的行；之后以更高提交版本
        // 重新写入同一主键确定地胜出（APPEND 也不再视为“已存在”）
        WriteResult re = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                List.of(Map.of("id", 1, "v", "new")))); // v3
        assertTrue(re.committed());
        assertEquals(1, t.readLatest().size());
        assertEquals("new", t.readLatest().get(0).get("v"));
        assertEquals("old", t.readAt(1).get(0).get("v"));
        assertEquals(0, t.readAt(2).size());

        // MERGE 覆盖同一主键同样确定
        t.commit(new WriteRequest(WriteMode.MERGE, 3L, List.of(Map.of("id", 1, "v", "merged")))); // v4
        assertEquals("merged", t.readLatest().get(0).get("v"));
        LOG.info("[delete:rewrite] deleteV=2 rewriteV=3 mergeV=4 headSnapshotId={} latestValue=merged basis=higher commitVersion wins over tombstone",
                t.currentSnapshot().snapshotId());
    }

    @Test
    void deletedRowsNeverResurrectAfterCompaction() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b")))); // v1
        t.commit(new WriteRequest(WriteMode.APPEND, 1L,
                List.of(Map.of("id", 3, "v", "c")))); // v2
        t.delete(new DeleteRequest(2L, List.of(Map.of("id", 1)))); // v3
        assertEquals(2, t.readLatest().size());

        Snapshot compacted = t.compact(); // v4：多文件 -> 单文件
        assertEquals(1, compacted.files().size());
        List<Map<String, Object>> after = t.readLatest();
        assertEquals(2, after.size());
        assertFalse(after.stream().anyMatch(m -> m.get("id").equals(1L)),
                "deleted key must not resurrect after compaction");
        // 历史版本不受影响
        assertEquals(2, t.readAt(1).size());
        assertEquals(3, t.readAt(2).size());
        LOG.info("[delete:compact] compactedVersion={} snapshotId={} files={} liveRows={} deletedKeyAbsent=true basis=compaction uses same merge rule as query",
                compacted.version(), compacted.snapshotId(), compacted.files().size(), after.size());
    }

    @Test
    void legacyDataFilesWithoutDeletedFlagRemainReadable() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b")))); // v1

        // 模拟旧版本写出的数据文件：行 JSON 里没有 deleted 字段
        Path legacy = warehouse.resolve("t/data/1-0.json");
        String modern = java.nio.file.Files.readString(legacy);
        assertTrue(modern.contains("\"deleted\""));
        String legacyJson = modern.replaceAll(",\\s*\"deleted\"\\s*:\\s*false", "");
        assertFalse(legacyJson.contains("deleted"));
        java.nio.file.Files.writeString(legacy, legacyJson);

        // 重开 catalog（仅从磁盘恢复）：旧格式文件按 deleted=false 解读
        Table reopened = new LocalCatalog(warehouse).openTable("t");
        assertEquals(2, reopened.readLatest().size());
        // 删除在旧文件之上正常工作
        WriteResult del = reopened.delete(new DeleteRequest(1L, List.of(Map.of("id", 1))));
        assertTrue(del.committed());
        assertEquals(1, reopened.readLatest().size());
        assertEquals(2, reopened.readAt(1).size());
        LOG.info("[delete:compat] legacy file without deleted flag readable; delete on top committedVersion={} snapshotId={} basis=missing deleted defaults to false",
                del.committedVersion(), del.snapshotId());
    }

    @Test
    void deleteInterleavedWithSchemaEvolutionReadsEachSnapshotWithItsOwnSchema() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b")))); // v1
        t.evolve(new SchemaChangeRequest(1L, List.of(
                new ColumnChange(ColumnChange.Kind.ADD, "note", null,
                        new ColumnSpec("note", DataType.STRING, true, "dft"))))); // v2
        t.evolve(new SchemaChangeRequest(2L, List.of(
                new ColumnChange(ColumnChange.Kind.RENAME, "v", "label", null)))); // v3
        t.delete(new DeleteRequest(3L, List.of(Map.of("id", 1)))); // v4
        t.evolve(new SchemaChangeRequest(4L, List.of(
                new ColumnChange(ColumnChange.Kind.DROP, "note", null, null)))); // v5

        // v1：旧列名 v，无 note
        List<Map<String, Object>> r1 = t.readAt(1);
        assertEquals(2, r1.size());
        assertTrue(r1.get(0).containsKey("v"));
        assertFalse(r1.get(0).containsKey("label"));
        // v3：改名后列名 label，note 用缺省值
        List<Map<String, Object>> r3 = t.readAt(3);
        assertEquals("a", r3.stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("label"));
        assertEquals("dft", r3.get(0).get("note"));
        // v4：id=1 已删，值不能错列（label 读到的必须还是原 v 列的值）
        List<Map<String, Object>> r4 = t.readAt(4);
        assertEquals(1, r4.size());
        assertEquals(2L, r4.get(0).get("id"));
        assertEquals("b", r4.get(0).get("label"));
        // v5（头）：note 已删，只剩 id + label
        List<Map<String, Object>> r5 = t.readLatest();
        assertEquals(1, r5.size());
        assertFalse(r5.get(0).containsKey("note"));
        assertEquals("b", r5.get(0).get("label"));

        for (Snapshot s : t.history()) {
            LOG.info("[delete:evolution] version={} snapshotId={} op={} columns={} rows={}",
                    s.version(), s.snapshotId(), s.operation(),
                    s.schema().columns().stream().map(c -> c.id() + ":" + c.name()).toList(),
                    t.readRows(s).size());
        }
    }
}
