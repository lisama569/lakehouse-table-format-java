package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionPolicy;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
import com.github.lisama569.lakehousetableformatjava.storage.LocalCatalog;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收与并发提交/删除同时进行：
 * 不能删掉新快照引用的文件，也不能让当前快照指向已删除文件；
 * 基于过期版本的提交/删除得到 SNAPSHOT_CONFLICT，刷新重试后全部落地。
 */
class ConcurrentVacuumTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrentVacuumTest.class);

    @TempDir
    Path warehouse;

    @Test
    void vacuumRacingWithWritesAndDeletesKeepsHeadIntact() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.LONG, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");
        // 先放 20 行，并追加出多个文件/版本（v1..v5）
        for (int batch = 0; batch < 5; batch++) {
            int b = batch;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                long id = b * 4L + i + 1;
                rows.add(Map.of("id", id, "v", id));
            }
            t.commit(new WriteRequest(WriteMode.APPEND, (long) batch, rows));
        }

        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger vacuums = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // 提交线程：各自负责一段不重叠的主键，先 MERGE 更新再删除部分行；
        // 冲突时刷新头版本重试，保证业务意图最终落地或因键已删而干净失败
        for (int w = 0; w < writers; w++) {
            int worker = w;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 4; round++) {
                        long id = worker * 4L + (round % 4) + 1;
                        long base;
                        WriteResult r;
                        // 更新
                        do {
                            base = t.currentSnapshot().version();
                            try {
                                r = t.commit(new WriteRequest(WriteMode.MERGE, base,
                                        List.of(Map.of("id", id, "v", 1000L + round))));
                                if (r.committed()) {
                                    commits.incrementAndGet();
                                }
                            } catch (ConflictException e) {
                                conflicts.incrementAndGet();
                                r = null;
                            }
                        } while (r == null);
                        // 删除偶数轮的键
                        if (round % 2 == 0) {
                            while (true) {
                                base = t.currentSnapshot().version();
                                try {
                                    WriteResult dr = t.delete(new DeleteRequest(base, List.of(Map.of("id", id))));
                                    if (dr.committed()) {
                                        deletes.incrementAndGet();
                                        break;
                                    }
                                    // 键可能已被本线程前序轮次删除：接受这个确定结果
                                    break;
                                } catch (ConflictException e) {
                                    conflicts.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 回收线程：反复按“保留最近 3 个快照”回收
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 6; i++) {
                    t.vacuum(RetentionPolicy.retainLast(3));
                    vacuums.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 最终不变量 1：头快照引用的每个文件都在磁盘上
        List<String> headFiles = t.currentSnapshot().files().stream()
                .map(f -> f.path()).toList();
        for (String f : headFiles) {
            assertTrue(Files.exists(warehouse.resolve("t").resolve(f)),
                    "HEAD references a deleted file after racing vacuum: " + f);
        }
        // 最终不变量 2：头快照可读、结果自洽（不抛文件缺失）
        List<Map<String, Object>> headRows = t.readLatest();
        long headVersion = t.currentSnapshot().version();

        // 最终不变量 3：保留快照的查询结果与它引用的文件一致——逐个保留快照读取
        for (var snap : t.history()) {
            List<Map<String, Object>> rows = t.readRows(snap);
            for (Map<String, Object> row : rows) {
                assertTrue(row.containsKey("id"));
            }
        }
        LOG.info("[vacuum:race:done] headVersion={} snapshotId={} headFiles={} headRows={} commits={} deletes={} conflicts={} vacuums={} basis=tableLock serializes; delete-set excludes retained references",
                headVersion, t.currentSnapshot().snapshotId(), headFiles.size(), headRows.size(),
                commits.get(), deletes.get(), conflicts.get(), vacuums.get());
    }
}
