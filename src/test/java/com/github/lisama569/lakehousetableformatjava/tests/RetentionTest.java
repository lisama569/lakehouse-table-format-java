package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.RetentionRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionResult;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.storage.LocalCatalog;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetentionTest {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionTest.class);

    @TempDir
    Path warehouse;

    private Table newTable(LocalCatalog catalog) {
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        return catalog.openTable("t");
    }

    private Set<String> dataFilesOnDisk() throws Exception {
        try (Stream<Path> s = Files.list(warehouse.resolve("t/data"))) {
            return s.map(p -> "data/" + p.getFileName()).collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void keepLastSnapshotsExpiresOlderOnesAndOrphanFiles() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        // v1..v3：每次 APPEND 产生一个独立数据文件（文件列表累积）
        for (int i = 1; i <= 3; i++) {
            t.commit(new WriteRequest(WriteMode.APPEND, (long) (i - 1),
                    List.of(Map.of("id", i, "v", "v" + i))));
        }
        t.compact(); // v4：重写为单文件 4-compact.json，旧文件仅被 v1..v3 引用
        t.commit(new WriteRequest(WriteMode.APPEND, 4L,
                List.of(Map.of("id", 4, "v", "v4")))); // v5
        // 回收前记录保留范围内每个版本的查询结果
        List<Map<String, Object>> beforeV4 = t.readAt(4);
        List<Map<String, Object>> beforeV5 = t.readAt(5);

        RetentionResult r = t.expireSnapshots(new RetentionRequest(2, null));

        assertEquals(5L, r.headVersion());
        assertEquals(List.of(0L, 1L, 2L, 3L), r.expiredSnapshotVersions());
        assertEquals(List.of(4L, 5L), r.retainedSnapshotVersions());
        // v1..v3 的旧文件不再被任何保留快照引用，应被删除；v4/v5 引用的文件必须保留
        assertEquals(Set.of("data/1-0.json", "data/2-0.json", "data/3-0.json"),
                new HashSet<>(r.deletedDataFiles()));
        assertEquals(Set.of("data/4-compact.json", "data/5-0.json"),
                new HashSet<>(r.retainedDataFiles()));
        assertEquals(new HashSet<>(r.retainedDataFiles()), dataFilesOnDisk());

        // 保留范围内逐条一致
        assertEquals(beforeV4, t.readAt(4));
        assertEquals(beforeV5, t.readAt(5));
        assertEquals(beforeV5, t.readLatest());
        // 过期版本不再可读
        assertThrows(IllegalArgumentException.class, () -> t.readAt(1));
        assertEquals(List.of(4L, 5L), t.history().stream().map(Snapshot::version).toList());
        LOG.info("[retention:keepLast] headVersion={} headSnapshotId={} expired={} retained={} deletedFiles={} basis=keepLastSnapshots=2",
                r.headVersion(), r.headSnapshotId(), r.expiredSnapshotVersions(),
                r.retainedSnapshotVersions(), r.deletedDataFiles());
    }

    @Test
    void retainFromVersionKeepsHeadAndReferencedFiles() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1, "v", "a")))); // v1
        t.commit(new WriteRequest(WriteMode.APPEND, 1L, List.of(Map.of("id", 2, "v", "b")))); // v2
        t.commit(new WriteRequest(WriteMode.APPEND, 2L, List.of(Map.of("id", 3, "v", "c")))); // v3
        Snapshot compacted = t.compact(); // v4：重写为单文件 4-compact.json
        List<Map<String, Object>> headBefore = t.readLatest();

        // 保留 v4 起：v0..v3 过期；v1..v3 的旧文件不再被引用，compact 文件必须保留
        RetentionResult r = t.expireSnapshots(new RetentionRequest(null, 4L));
        assertEquals(List.of(0L, 1L, 2L, 3L), r.expiredSnapshotVersions());
        assertEquals(List.of(4L), r.retainedSnapshotVersions());
        assertEquals(List.of(compacted.files().get(0).path()), r.retainedDataFiles());
        assertEquals(3, r.deletedDataFiles().size());
        assertEquals(headBefore, t.readLatest());

        // 回收后重开 catalog（仅从磁盘恢复）头快照仍完整可读
        Table reopened = new LocalCatalog(warehouse).openTable("t");
        assertEquals(headBefore, reopened.readLatest());
        assertEquals(4L, reopened.currentSnapshot().version());
        LOG.info("[retention:fromVersion] headVersion={} headSnapshotId={} retainedFiles={} deletedFiles={} reopenConsistent=true basis=retainFromVersion=4",
                r.headVersion(), r.headSnapshotId(), r.retainedDataFiles(), r.deletedDataFiles());
    }

    @Test
    void headSnapshotIsNeverExpiredEvenWhenPolicyWouldExcludeIt() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1, "v", "a")))); // v1
        // retainFromVersion 大于头版本：按头版本处理，头快照与其文件必须保留
        RetentionResult r = t.expireSnapshots(new RetentionRequest(null, 99L));
        assertEquals(List.of(0L), r.expiredSnapshotVersions());
        assertEquals(List.of(1L), r.retainedSnapshotVersions());
        assertEquals(1, t.readLatest().size());
        LOG.info("[retention:headPinned] headVersion={} retained={} basis=head always retained",
                r.headVersion(), r.retainedSnapshotVersions());
    }

    @Test
    void retentionPolicyMustBeWellFormed() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        assertThrows(IllegalArgumentException.class, () -> new RetentionRequest(null, null));
        assertThrows(IllegalArgumentException.class, () -> new RetentionRequest(2, 3L));
        assertThrows(IllegalArgumentException.class, () -> new RetentionRequest(0, null));
        assertThrows(IllegalArgumentException.class, () -> new RetentionRequest(null, -1L));
        assertThrows(IllegalArgumentException.class, () -> t.expireSnapshots(null));
        LOG.info("[retention:invalid] basis=exactly one policy, keepLast>=1, fromVersion>=0");
    }

    @Test
    void retentionRacingWithCommitsNeverLosesReferencedFiles() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 0, "v", "seed")))); // v1

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> committers = new ArrayList<>();

        // 提交线程：各自循环“刷新到最新版本 -> 追加一行”，冲突时刷新重试
        for (int i = 0; i < threads; i++) {
            int id = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 10; k++) {
                        while (true) {
                            long base = t.currentSnapshot().version();
                            try {
                                t.commit(new WriteRequest(WriteMode.APPEND, base,
                                        List.of(Map.of("id", 1000 + id * 100 + k, "v", "x"))));
                                break;
                            } catch (com.github.lisama569.lakehousetableformatjava.exception.ConflictException retry) {
                                // 并发提交冲突：刷新基版本后重试
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
        }
        // 回收线程：并发地反复只保留最近 1 个快照
        pool.submit(() -> {
            try {
                start.await();
                for (int k = 0; k < 15; k++) {
                    t.expireSnapshots(new RetentionRequest(1, null));
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        if (failure.get() != null) {
            throw new AssertionError("concurrent retention/commit failed", failure.get());
        }
        // 不变量：当前头快照引用的每个文件都真实存在，头快照完整可读
        Snapshot head = t.currentSnapshot();
        Set<String> onDisk = dataFilesOnDisk();
        for (var f : head.files()) {
            assertTrue(onDisk.contains(f.path()),
                    "head snapshot references missing file " + f.path());
        }
        assertEquals(41, t.readLatest().size()); // 1 seed + 4*10 commits
        LOG.info("[retention:race] headVersion={} headSnapshotId={} headFiles={} onDiskFiles={} rows={} basis=commitLock serializes commit vs retention",
                head.version(), head.snapshotId(), head.files().size(), onDisk.size(),
                t.readLatest().size());
    }
}
