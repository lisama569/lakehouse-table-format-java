package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.evolve.SchemaChange;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 结构演进（增/删/改列）与数据文件整理：缺省语义稳定、历史快照不错列、整理前后查询一致。 */
class SchemaEvolutionAndCompactTest {

    private Map<String, Object> row(long id, String name) {
        return new LinkedHashMap<>(Map.of("id", id, "name", name));
    }

    @Test
    void addColumnHasStableDefaultForOldRowsAndAppliesToNewRows(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "old"))); // v1, schema v0
        long v1 = t.currentSnapshot().getVersion();

        // 新增可空列 region，缺省值 "UNKNOWN"
        var ev = t.evolve(v1, List.of(
                new SchemaChange.AddColumn("region", DataType.STRING, true, "UNKNOWN"))); // v2, schema v1
        assertEquals(1L, ev.schemaId());

        // 历史快照仍按其冻结的旧 schema 解释：无 region 列
        assertFalse(t.readAt(v1).get("1").containsKey("region"));
        // 新快照下旧记录在新列上稳定取缺省值
        assertEquals("UNKNOWN", t.readAt(2).get("1").get("region"));

        // 新增列后写入新记录可显式给值；未显式提供的旧列语义不变
        Map<String, Object> r2 = row(2, "new");
        r2.put("region", "us-east");
        t.write(2, WriteMode.APPEND, List.of(r2)); // v3
        var v3 = t.readAt(3);
        assertEquals("us-east", v3.get("2").get("region"));
        assertEquals("UNKNOWN", v3.get("1").get("region")); // 旧行缺省值保持
        System.out.printf("[TEST] ADD COLUMN 后 提交版本 v%d 快照 %s：旧行缺省=UNKNOWN%n",
                ev.version(), ev.snapshotId());
    }

    @Test
    void addNotNullColumnWithoutDefaultIsRejected(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "old")));
        var ex = assertThrows(CommitRejectedException.class, () -> t.evolve(1, List.of(
                new SchemaChange.AddColumn("tenant", DataType.STRING, false, null))));
        assertEquals(RejectReason.INVALID_SCHEMA_CHANGE, ex.getReason());
        assertTrue(ex.getMessage().contains("非空列必须提供非空缺省值"));
        // 被拒绝后版本不变
        assertEquals(1, t.currentSnapshot().getVersion());
    }

    @Test
    void renameColumnDoesNotMisalignHistoricalSnapshot(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "alice"))); // v1
        long v1 = t.currentSnapshot().getVersion();

        var ev = t.evolve(v1, List.of(new SchemaChange.RenameColumn("name", "full_name"))); // v2
        // 历史快照按旧 schema：列名仍是 name 且值正确，不会错列到别的 id
        var oldRow = t.readAt(v1).get("1");
        assertEquals("alice", oldRow.get("name"));
        assertFalse(oldRow.containsKey("full_name"));
        // 新快照按新 schema：值通过稳定列 id 关联到 full_name
        var newRow = t.readAt(ev.version()).get("1");
        assertEquals("alice", newRow.get("full_name"));
        assertFalse(newRow.containsKey("name"));

        // 改名后用新列名写入成功
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 2);
        r.put("full_name", "bob");
        t.write(ev.version(), WriteMode.APPEND, List.of(r));
        assertEquals("bob", t.readLatest().get("2").get("full_name"));
        System.out.printf("[TEST] RENAME name->full_name 版本 v%d 快照 %s：历史不错列%n",
                ev.version(), ev.snapshotId());
    }

    @Test
    void illegalEvolutionsAreRejectedWithDistinctMessages(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        // 重命名不存在的列
        assertEquals(RejectReason.INVALID_SCHEMA_CHANGE, assertThrows(CommitRejectedException.class,
                () -> t.evolve(0, List.of(new SchemaChange.RenameColumn("nope", "x")))).getReason());
        // 新增同名列
        assertEquals(RejectReason.INVALID_SCHEMA_CHANGE, assertThrows(CommitRejectedException.class,
                () -> t.evolve(0, List.of(new SchemaChange.AddColumn("name", DataType.STRING, true, null)))
        ).getReason());
        // 删除主键列
        assertEquals(RejectReason.INVALID_SCHEMA_CHANGE, assertThrows(CommitRejectedException.class,
                () -> t.evolve(0, List.of(new SchemaChange.DropColumn("id")))).getReason());
        // 删除不存在的列
        assertEquals(RejectReason.INVALID_SCHEMA_CHANGE, assertThrows(CommitRejectedException.class,
                () -> t.evolve(0, List.of(new SchemaChange.DropColumn("ghost")))).getReason());
        // 结构变更也受 OCC 保护：先制造 v1
        t.write(0, WriteMode.APPEND, List.of(row(1, "a")));
        assertEquals(RejectReason.STALE_SNAPSHOT, assertThrows(CommitRejectedException.class,
                () -> t.evolve(0, List.of(new SchemaChange.AddColumn("c1", DataType.STRING, true, "d")))
        ).getReason());
    }

    @Test
    void dropColumnKeepsOldSnapshotIntactAndHidesColumnAfterwards(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "alice"))); // v1
        long v1 = t.currentSnapshot().getVersion();
        var ev = t.evolve(v1, List.of(new SchemaChange.DropColumn("score"))); // score 可空，非主键 -> 允许
        // 历史快照仍包含 score
        assertEquals(0.0, t.readAt(v1).get("1").get("score"));
        // 新快照不再包含 score，主键与其他列不受影响
        var cur = t.readAt(ev.version()).get("1");
        assertFalse(cur.containsKey("score"));
        assertEquals("alice", cur.get("name"));
    }

    @Test
    void compactProducesIdenticalQueryResultsAndReducesDataFiles(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "a"))); // v1: file1
        t.write(1, WriteMode.MERGE, List.of(row(1, "a2"), row(2, "b"))); // v2: file2
        t.write(2, WriteMode.MERGE, List.of(row(2, "b2"), row(3, "c"))); // v3: file3
        long beforeVersion = t.currentSnapshot().getVersion();
        int filesBefore = t.currentSnapshot().getSnapshotDataFiles().size();
        Map<String, Map<String, Object>> before = t.readAt(beforeVersion);
        assertEquals(3, filesBefore);

        var report = t.compact(beforeVersion); // v4
        int filesAfter = t.currentSnapshot().getSnapshotDataFiles().size();
        assertEquals(1, filesAfter, "整理后快照只引用 1 个合并文件");

        // 整理前后的查询结果必须完全一致（键集合与每行列值）
        Map<String, Map<String, Object>> after = t.readAt(report.version());
        assertEquals(before, after);
        // 历史快照依旧可复现（旧文件被保留）
        assertEquals(2, t.readAt(2).size());
        System.out.printf("[TEST] COMPACT v%d 快照 %s 文件数 %d->%d，结果完全一致，依据=%s%n",
                report.version(), report.snapshotId(), filesBefore, filesAfter, report.basis());
    }

    @Test
    void fullEvolutionChainSnapshotsEachRemainConsistent(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "v1")));          // v1 schema0
        t.evolve(1, List.of(new SchemaChange.AddColumn("tag", DataType.STRING, true, "none"))); // v2 schema1
        Map<String, Object> r = row(2, "v2");
        r.put("tag", "hot");
        t.write(2, WriteMode.APPEND, List.of(r));                     // v3 schema1
        t.evolve(3, List.of(new SchemaChange.RenameColumn("tag", "label"))); // v4 schema2

        // 每个历史版本按各自 schema 稳定复现
        assertEquals("v1", t.readAt(1).get("1").get("name"));
        assertEquals("none", t.readAt(2).get("1").get("tag"));
        assertEquals("hot", t.readAt(3).get("2").get("tag"));
        assertEquals("hot", t.readAt(4).get("2").get("label"));
        assertEquals("none", t.readAt(4).get("1").get("label"));
        assertEquals("v1", t.readAt(4).get("1").get("name"));

        t.listSnapshots().forEach(s -> System.out.printf(
                "[CHAIN] v%d snapshot=%s schema=v%d op=%s files=%d%n",
                s.getVersion(), s.getSnapshotId(), s.getSchemaId(), s.getOperation(),
                s.getSnapshotDataFiles().size()));
    }

    @Test
    void droppedColumnIdIsNeverReused(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "keep"))); // v1: id,name,score
        // score 是第 3 列（id=2）。删除后新增一列同名不同类型，若复用 id=2 就会错读历史 score 值。
        t.evolve(1, List.of(new SchemaChange.DropColumn("score")));          // v2
        t.evolve(2, List.of(new SchemaChange.AddColumn("score", DataType.STRING, true, "fresh"))); // v3
        long v3 = t.currentSnapshot().getVersion();
        var rows = t.readAt(v3);
        // 旧记录的新 score 列必须取新列缺省值，而不是把旧 DOUBLE 值 0.0 错配成 STRING
        assertEquals("fresh", rows.get("1").get("score"));
        System.out.printf("[TEST] 列 id 不复用：DROP 再 ADD 后旧行 score=%s（而非历史数值错列）%n",
                rows.get("1").get("score"));
    }
}
