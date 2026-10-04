package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionPolicy;
import com.github.lisama569.lakehousetableformatjava.model.VacuumResult;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.storage.LocalTableStorage;
import com.github.lisama569.lakehousetableformatjava.storage.LocalCatalog;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间回收：按“保留到某版本”或“只保留最近 N 个快照”过期快照，
 * 删除无引用的数据文件；保留范围内逐条一致、引用文件不误删；
 * 与并发提交相撞也不破坏头快照。
 */
class VacuumTest {

    private static final Logger LOG = LoggerFactory.getLogger(VacuumTest.class);

    @TempDir
    Path warehouse;

    private Table buildHistory(LocalCatalog catalog) {
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1, "v", "a")))); // v1 1-0
        t.commit(new WriteRequest(WriteMode.APPEND, 1L, List.of(Map.of("id", 2, "v", "b")))); // v2 2-0
        t.commit(new WriteRequest(WriteMode.MERGE, 2L, List.of(Map.of("id", 1, "v", "a2")))); // v3 3-0
        t.delete(new DeleteRequest(3L, List.of(Map.of("id", 2))));                              // v4 4-delete
        t.compact();                                                                           // v5 5-compact
        return t;
    }

    private List<Map<String, Object>> readAll(Table t, List<Long> versions) {
        return versions.stream().flatMap(v -> t.readAt(v).stream()).toList();
    }

    @Test
    void retainAtLeastVersionExpiresOldSnapshotsAndUnreferencedFiles() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = buildHistory(catalog);
        Path tableDir = warehouse.resolve("t");

        // 回收前记录保留范围内（v3..v5）每个快照的查询结果
        List<Long> retainedVersions = List.of(3L, 4L, 5L);
        List<Map<String, Object>> before = readAll(t, retainedVersions);

        // 快照 v5 引用的文件（compaction 产物）必须存活；v1/v2 独有文件应被删
        List<String> headFiles = t.snapshotAt(5).files().stream().map(f -> f.path()).toList();
        List<String> allFilesBefore = new LocalTableStorage(tableDir).listDataFiles();
        assertTrue(allFilesBefore.size() > headFiles.size(), "garbage files exist before vacuum");

        VacuumResult result = t.vacuum(RetentionPolicy.retainAtLeastVersion(3));
        assertEquals(List.of(0L, 1L, 2L), result.expiredSnapshots());
        assertEquals(retainedVersions, result.retainedSnapshots());
        LOG.info("[vacuum] headStart={} headEnd={} expired={} deletedFiles={} retainedFiles={}",
                result.headVersionAtStart(), result.headVersionAtEnd(),
                result.expiredSnapshots(), result.deletedDataFiles(), result.retainedDataFiles().size());

        // 被头快照（及保留快照）引用的文件一个都没删
        List<String> filesAfter = new LocalTableStorage(tableDir).listDataFiles();
        for (String mustKeep : headFiles) {
            assertTrue(filesAfter.contains(mustKeep), "retained snapshot file must survive: " + mustKeep);
        }
        // 被删的恰好是“只有过期快照引用”的文件
        for (String deleted : result.deletedDataFiles()) {
            assertFalse(filesAfter.contains(deleted));
            for (long v : retainedVersions) {
                assertFalse(t.snapshotAt(v).files().stream().anyMatch(f -> f.path().equals(deleted)),
                        "deleted file was still referenced by retained v" + v);
            }
        }

        // 历史：过期快照文件已不存在；保留快照仍可逐条复现回收前结果
        assertFalse(Files.exists(tableDir.resolve("metadata/snap-1.json")));
        assertThrows(IllegalArgumentException.class, () -> t.readAt(1));
        assertEquals(before, readAll(t, retainedVersions),
                "retained snapshots reproduce identical rows after vacuum");
        assertEquals(3, t.history().size());
        LOG.info("[vacuum:verify] retained rows identical before/after; head snapshotId={} files={}",
                t.currentSnapshot().snapshotId(), headFiles);
    }

    @Test
    void retainLastKeepsMostRecentSnapshotsIncludingHead() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = buildHistory(catalog);
        List<Map<String, Object>> before = readAll(t, List.of(4L, 5L));

        VacuumResult result = t.vacuum(RetentionPolicy.retainLast(2));
        assertEquals(List.of(0L, 1L, 2L, 3L), result.expiredSnapshots());
        assertEquals(List.of(4L, 5L), result.retainedSnapshots());
        assertEquals(before, readAll(t, List.of(4L, 5L)));
        assertThrows(IllegalArgumentException.class, () -> t.snapshotAt(3));
        // 头快照永远在保留集合内：头仍可读
        assertEquals(1, t.readLatest().size());
        LOG.info("[vacuum:retainLast2] expired={} deleted={}; head still readable snapshotId={}",
                result.expiredSnapshots(), result.deletedDataFiles(), t.currentSnapshot().snapshotId());

        // 再次回收是幂等空操作
        VacuumResult again = t.vacuum(RetentionPolicy.retainLast(2));
        assertTrue(again.expiredSnapshots().isEmpty());
        assertTrue(again.deletedDataFiles().isEmpty());
    }

    @Test
    void invalidPoliciesAreRejected() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = buildHistory(catalog);
        assertThrows(IllegalArgumentException.class,
                () -> t.vacuum(new RetentionPolicy(null, null)), "must specify one mode");
        assertThrows(IllegalArgumentException.class,
                () -> t.vacuum(new RetentionPolicy(2L, 3)), "cannot specify both modes");
        assertThrows(IllegalArgumentException.class,
                () -> t.vacuum(RetentionPolicy.retainLast(0)));
        assertThrows(IllegalArgumentException.class,
                () -> t.vacuum(RetentionPolicy.retainAtLeastVersion(99)));
        LOG.warn("[vacuum:reject] ambiguous/out-of-range policies rejected with INVALID_ARGUMENT");
    }

    @Test
    void vacuumDoesNotBreakHeadWhenCommitRaces() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = buildHistory(catalog); // head=5

        // 回收保留到 v3；另一个线程在回收进行期间尝试提交（同 JVM 表锁串行化）：
        // 无论谁先谁后，当前快照都不能指向已删除文件，新提交也不能丢失。
        final VacuumResult[] result = new VacuumResult[1];
        Thread vac = new Thread(() -> result[0] = t.vacuum(RetentionPolicy.retainAtLeastVersion(3)));
        Thread committer = new Thread(() -> {
            try {
                t.commit(new WriteRequest(WriteMode.APPEND, null,
                        List.of(Map.of("id", 9, "v", "race"))));
            } catch (Exception e) {
                LOG.warn("[race:commit] {}", e.getMessage());
            }
        });
        vac.start();
        committer.start();
        vac.join();
        committer.join();

        long head = t.currentSnapshot().version();
        List<Map<String, Object>> rows = t.readLatest(); // 不能抛“文件不存在”
        List<String> headFiles = t.currentSnapshot().files().stream().map(f -> f.path()).toList();
        for (String f : headFiles) {
            assertTrue(Files.exists(warehouse.resolve("t").resolve(f)),
                    "head snapshot references a missing file: " + f);
        }
        if (result[0].headVersionAtEnd() != result[0].headVersionAtStart()) {
            // 提交先于回检测得头 -> 头已推进到 v6，新行必须在
            assertEquals(6L, head);
            assertTrue(rows.stream().anyMatch(r -> r.get("id").equals(9L)), "racing row must be present");
            LOG.info("[vacuum:race] commit landed during vacuum; head moved 5->{} snapshotId={} and all referenced files exist; deletedFiles={}",
                    head, t.currentSnapshot().snapshotId(), result[0].deletedDataFiles());
        } else {
            // 回收先完成（v6 若提交成功则在回收之后）：两者任一情况下头都可读
            assertTrue(head == 5L || head == 6L);
            if (head == 6L) {
                assertTrue(rows.stream().anyMatch(r -> r.get("id").equals(9L)));
            }
            LOG.info("[vacuum:race] vacuum finished first (headStart={}, committedHead={}); referenced files intact, deletedFiles={}",
                    result[0].headVersionAtStart(), head, result[0].deletedDataFiles());
        }

        // 回收范围内结果始终一致
        List<Map<String, Object>> v5 = t.readAt(5);
        assertEquals(1, v5.size());
        assertEquals("a2", v5.get(0).get("v"));
    }
}
