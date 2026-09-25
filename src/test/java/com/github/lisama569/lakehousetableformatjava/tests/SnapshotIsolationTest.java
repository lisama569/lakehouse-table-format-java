package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LocalLakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 快照隔离与历史版本查询：任何已提交快照在其后都可被一致复现。 */
class SnapshotIsolationTest {

    private Map<String, Object> row(long id, String name) {
        return Map.of("id", id, "name", name);
    }

    @Test
    void everyCommittedSnapshotIsReproducible(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "v1-a")));                 // v1
        t.write(1, WriteMode.MERGE, List.of(row(1, "v2-a-updated"), row(2, "v2-b"))); // v2
        t.write(2, WriteMode.MERGE, List.of(row(2, "v3-b-updated")));        // v3

        // 各版本内容快照定格：后续提交不改变旧版本读到的结果
        assertEquals(0, t.readAt(0).size());
        var v1 = t.readAt(1);
        assertEquals(1, v1.size());
        assertEquals("v1-a", v1.get("1").get("name"));

        var v2 = t.readAt(2);
        assertEquals("v2-a-updated", v2.get("1").get("name"));
        assertEquals("v2-b", v2.get("2").get("name"));

        var v3 = t.readAt(3);
        assertEquals("v2-a-updated", v3.get("1").get("name")); // 未在 v3 提交中修改 -> 保持
        assertEquals("v3-b-updated", v3.get("2").get("name"));

        // 反复读取同一快照结果完全一致
        assertEquals(t.readAt(2), t.readAt(2));

        t.listSnapshots().forEach(s -> System.out.printf(
                "[TEST] 快照 v%d id=%s parent=%s op=%s dataFiles=%d%n",
                s.getVersion(), s.getSnapshotId(), s.getParentSnapshot(),
                s.getOperation(), s.getSnapshotDataFiles().size()));
    }

    @Test
    void staleBaseSnapshotIsRejectedAndCommittedResultIsPreserved(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "committed"))); // v1 已提交

        // 另一个写入仍基于 v0 -> 必须被明确拒绝（STALE_SNAPSHOT），且不得覆盖 v1
        CommitRejectedException ex = assertThrows(CommitRejectedException.class,
                () -> t.write(0, WriteMode.APPEND, List.of(row(2, "stale"))));
        assertEquals(RejectReason.STALE_SNAPSHOT, ex.getReason());
        assertTrue(ex.getMessage().contains("v1"), "拒绝信息需指出当前最新版本: " + ex.getMessage());

        assertEquals(1, t.currentSnapshot().getVersion());
        assertEquals(1, t.readLatest().size());
        assertEquals("committed", t.readLatest().get("1").get("name"));
        System.out.printf("[TEST] 过期写入被拒绝 reason=%s msg=%s%n", ex.getReason(), ex.getMessage());
    }

    @Test
    void unknownVersionIsRejectedAsSnapshotNotFound(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        var ex = assertThrows(CommitRejectedException.class,
                () -> t.write(99, WriteMode.APPEND, List.of(row(1, "x"))));
        assertEquals(RejectReason.SNAPSHOT_NOT_FOUND, ex.getReason());

        var ex2 = assertThrows(CommitRejectedException.class, () -> t.readAt(99));
        assertEquals(RejectReason.SNAPSHOT_NOT_FOUND, ex2.getReason());
    }

    @Test
    void snapshotsSurviveCatalogRestart(@TempDir Path dir) {
        var catalog1 = LakehouseTestSupport.catalog(dir);
        Table t = LakehouseTestSupport.exampleTable(catalog1, "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "persisted")));

        Table reopened = new LocalLakehouseCatalog(dir).loadTable("t");
        assertEquals(1, reopened.currentSnapshot().getVersion());
        assertEquals("persisted", reopened.readAt(1).get("1").get("name"));
        assertEquals(0, reopened.readAt(0).size());
    }
}
