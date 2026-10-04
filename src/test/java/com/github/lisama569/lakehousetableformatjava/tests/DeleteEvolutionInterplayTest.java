package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.ColumnChange;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除、结构演进（加列/改名/删列）与 compaction 穿插发生后，
 * 每一个历史快照都必须按它当时的 schema 原样复现，列不错位、被删行不复活。
 */
class DeleteEvolutionInterplayTest {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteEvolutionInterplayTest.class);

    @TempDir
    Path warehouse;

    @Test
    void interleavedDeleteRenameAddDropAndCompactStayReproducible() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");

        // v1：三行（列 id=1, v=2）
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("id", 1, "v", "a"),
                Map.of("id", 2, "v", "b"),
                Map.of("id", 3, "v", "c"))));
        // v2：删除 id=2（墓碑文件，按列 ID 存主键）
        assertTrue(t.delete(new DeleteRequest(1L, List.of(Map.of("id", 2)))).committed());
        // v3：v 改名为 val（列 ID 2 不变）
        t.evolve(new SchemaChangeRequest(2L, List.of(
                new ColumnChange(ColumnChange.Kind.RENAME, "v", "val", null))));
        // v4：新增可空列 note（ID 3）
        Snapshot v4 = t.evolve(new SchemaChangeRequest(3L, List.of(
                new ColumnChange(ColumnChange.Kind.ADD, "note", null,
                        new ColumnSpec("note", DataType.STRING, true, null)))));
        // v5：id=3 用新列名/新列写入，且重新插入 id=2
        t.commit(new WriteRequest(WriteMode.MERGE, 4L, List.of(
                Map.of("id", 2, "val", "b2", "note", "reborn"),
                Map.of("id", 3, "val", "c2", "note", "three"))));
        // v6：再删 id=3
        assertTrue(t.delete(new DeleteRequest(5L, List.of(Map.of("id", 3)))).committed());
        // v7：删除列 note（墓碑文件里没有列 3 的值，按当时 schema 投影）
        t.evolve(new SchemaChangeRequest(6L, List.of(
                new ColumnChange(ColumnChange.Kind.DROP, "note", null, null))));

        // 累积多个物理文件后做 compaction
        Snapshot headBefore = t.currentSnapshot();
        assertTrue(headBefore.files().size() > 1, "multiple files before compaction");
        Snapshot compacted = t.compact(); // v8

        // ---- 逐快照按当时 schema 复现 ----
        // v1：三行，列名 id/v
        List<Map<String, Object>> s1 = t.readAt(1);
        assertEquals(3, s1.size());
        assertEquals("a", s1.get(0).get("v"));
        assertTrue(s1.get(0).containsKey("v"));
        assertFalse(s1.get(0).containsKey("val"));

        // v2：id=2 已删；另两行仍按旧列名 v
        List<Map<String, Object>> s2 = t.readAt(2);
        assertEquals(2, s2.size());
        assertFalse(s2.stream().anyMatch(m -> m.get("id").equals(2L)));

        // v3：改名后，同样的按 ID 数据以 val 呈现，不错列
        List<Map<String, Object>> s3 = t.readAt(3);
        assertEquals(2, s3.size());
        assertEquals("a", s3.stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("val"));
        assertFalse(s3.get(0).containsKey("v"));

        // v4：新增列，旧行 note 稳定补 null
        assertEquals(null, t.readAt(4).stream().filter(m -> m.get("id").equals(1L))
                .findFirst().orElseThrow().get("note"));

        // v5：id=2 删除后重新插入可见；id=3 更新
        List<Map<String, Object>> s5 = t.readAt(5);
        assertEquals(3, s5.size());
        Map<String, Object> reborn = s5.stream().filter(m -> m.get("id").equals(2L)).findFirst().orElseThrow();
        assertEquals("b2", reborn.get("val"));
        assertEquals("reborn", reborn.get("note"));

        // v6：id=3 再次被删
        assertEquals(2, t.readAt(6).size());
        assertFalse(t.readAt(6).stream().anyMatch(m -> m.get("id").equals(3L)));

        // v7：note 列已删，投影中没有该列；id=2 的 val 不受影响（无错列）
        List<Map<String, Object>> s7 = t.readAt(7);
        assertEquals(2, s7.size());
        assertFalse(s7.get(0).containsKey("note"));
        assertEquals("b2", s7.stream().filter(m -> m.get("id").equals(2L)).findFirst().orElseThrow().get("val"));

        // v8 compaction：结果与 v7 逐条一致，被删行不复活；文件收敛为 1
        List<Map<String, Object>> s8 = t.readRows(compacted);
        assertEquals(s7, s8, "compaction must be a no-op semantically");
        assertEquals(1, compacted.files().size());
        LOG.info("[interplay] versions 1..8 reproduced with their own schemas; compact v7==v8 rows={} snapshotId={} basis=columnId projection + tombstone coordinates",
                s8.size(), compacted.snapshotId());

        // 重开 catalog 再复现一遍（持久化往返）
        Table reopened = new LocalCatalog(warehouse).openTable("t");
        assertEquals(s8, reopened.readLatest());
        assertEquals(3, reopened.readAt(1).size());
        assertEquals(2, reopened.readAt(2).size());
    }

    @Test
    void deleteAfterRenameOfPrimaryKeyColumnUsesCurrentName() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1, "v", "a"))));
        // 主键列改名（ID 不变）后，删除必须按新列名定位
        t.evolve(new SchemaChangeRequest(1L, List.of(
                new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null))));
        var r = t.delete(new DeleteRequest(2L, List.of(Map.of("id", 1))));
        assertFalse(r.committed(), "old pk name must be rejected as non-pk column");
        assertEquals("UNKNOWN_COLUMN", r.rejected().get(0).reason().name());
        var ok = t.delete(new DeleteRequest(2L, List.of(Map.of("order_id", 1))));
        assertTrue(ok.committed());
        assertEquals(0, t.readLatest().size());
        // 历史 v1 仍按旧列名复现
        assertEquals(1L, t.readAt(1).get(0).get("id"));
        LOG.info("[interplay:rename-pk] delete by order_id (columnId=1) committedVersion={} snapshotId={}; v1 still reads id, no misalignment",
                ok.committedVersion(), ok.snapshotId());
    }
}
