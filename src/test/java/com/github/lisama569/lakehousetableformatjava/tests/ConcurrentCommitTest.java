package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitReport;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发提交：OCC 下同一过期基线只允许一个提交成功，其余以 STALE_SNAPSHOT 被拒绝。 */
class ConcurrentCommitTest {

    private Map<String, Object> row(long id, String name) {
        return Map.of("id", id, "name", name);
    }

    @Test
    void sameBaseSnapshot_onlyOneWriterWinsOthersRejectedAsStale(@TempDir Path dir) throws Exception {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<RejectReason> rejectReasons = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final long id = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    CommitReport report = t.write(0, WriteMode.APPEND, List.of(row(id, "w" + id)));
                    success.incrementAndGet();
                    System.out.printf("[CONCURRENT-ACCEPT] writer=%d 提交版本=v%d 快照=%s 依据=%s%n",
                            id, report.version(), report.snapshotId(), report.basis());
                } catch (CommitRejectedException ex) {
                    rejectReasons.add(ex.getReason());
                    System.out.printf("[CONCURRENT-REJECT] writer=%d 原因=%s 依据=%s%n",
                            id, ex.getReason(), ex.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, success.get(), "同基线下有且仅有一个提交成功");
        assertEquals(threads - 1, rejectReasons.size());
        assertTrue(rejectReasons.stream().allMatch(r -> r == RejectReason.STALE_SNAPSHOT),
                "其余全部必须是 STALE_SNAPSHOT，实际: " + rejectReasons);
        assertEquals(1, t.currentSnapshot().getVersion(), "过期写入绝不覆盖已提交结果");
        assertEquals(1, t.readLatest().size());
    }

    @Test
    void disjointKeyWritersAllSucceedWhenEachRetriesOnLatest(@TempDir Path dir) throws Exception {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger committed = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final long id = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    // 冲突重试范式：读到过期就刷新基线再提交
                    boolean done = false;
                    while (!done) {
                        long base = t.currentSnapshot().getVersion();
                        try {
                            t.write(base, WriteMode.APPEND, List.of(row(id, "w" + id)));
                            committed.incrementAndGet();
                            done = true;
                        } catch (CommitRejectedException ex) {
                            if (ex.getReason() != RejectReason.STALE_SNAPSHOT) {
                                unexpected.add(ex);
                                done = true;
                            }
                        } catch (RuntimeException ex) {
                            unexpected.add(ex);
                            done = true;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(threads, committed.get(), () -> "未预期异常: "
                + unexpected.stream().map(Throwable::toString).toList());
        assertEquals(threads, t.readLatest().size());
        System.out.printf("[CONCURRENT-RETRY] %d 个不相交主键写入全部串行提交成功，最终版本 v%d%n",
                threads, t.currentSnapshot().getVersion());
    }
}
