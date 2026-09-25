package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentCommitTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrentCommitTest.class);

    @TempDir
    Path warehouse;

    @Test
    void concurrentCommitsOnSameBaseVersionExactlyOneWins() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // 所有线程都基于 v0（过期快照竞争）插入不同主键
        for (int i = 1; i <= threads; i++) {
            long id = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    WriteResult r = t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                            List.of(Map.of("id", id, "v", "w" + id))));
                    if (r.committed()) {
                        committed.incrementAndGet();
                        LOG.info("[concurrent:accept] id={} committedVersion={} snapshotId={} basis=headCAS base=0",
                                id, r.committedVersion(), r.snapshotId());
                    }
                } catch (ConflictException e) {
                    conflicts.incrementAndGet();
                    LOG.warn("[concurrent:reject] id={} reason=SNAPSHOT_CONFLICT expectedVersion={} currentVersion={} basis=OCC",
                            id, e.expectedVersion(), e.currentVersion());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, committed.get(), "exactly one stale-base commit may win");
        assertEquals(threads - 1, conflicts.get(), "all losers rejected with SNAPSHOT_CONFLICT");
        assertEquals(1L, t.currentSnapshot().version(), "head advanced exactly once");
        assertEquals(1, t.readLatest().size(), "only the winning row is visible");
        LOG.info("[concurrent:summary] threads={} committed={} conflicts={} headVersion={} snapshotId={}",
                threads, committed.get(), conflicts.get(),
                t.currentSnapshot().version(), t.currentSnapshot().snapshotId());
    }

    @Test
    void sequentialRetryAfterConflictAllRowsEventuallyPresent() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null)),
                List.of("id"));
        Table t = catalog.openTable("t");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            long id = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (true) {
                    long base = t.currentSnapshot().version();
                    try {
                        WriteResult r = t.commit(new WriteRequest(WriteMode.APPEND, base,
                                List.of(Map.of("id", id))));
                        if (r.committed()) {
                            LOG.info("[retry:accept] id={} baseVersion={} committedVersion={} snapshotId={}",
                                    id, base, r.committedVersion(), r.snapshotId());
                            return;
                        }
                    } catch (ConflictException e) {
                        LOG.warn("[retry:retry] id={} stale base={} current={}",
                                id, e.expectedVersion(), e.currentVersion());
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads, t.readLatest().size());
        assertEquals(threads, t.currentSnapshot().version());
        assertTrue(t.currentSnapshot().version() == threads);
    }
}
