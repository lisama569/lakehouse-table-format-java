package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.exception.SchemaEvolutionException;
import com.github.lisama569.lakehousetableformatjava.model.ColumnChange;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主键列改名（含复合主键）及其与删行、加列、删列、整理、并发提交穿插的覆盖。
 *
 * <p>核心不变量：列以稳定列 ID 寻址，主键改名只改主键定义中的名字，
 * 主键值编码不变，因此改名前后写入/删除/合并无缝衔接；每个历史快照
 * 仍按它自己那一版 schema 把值原样读出。
 */
class PrimaryKeyRenameTest {

    private static final Logger LOG = LoggerFactory.getLogger(PrimaryKeyRenameTest.class);

    @TempDir
    Path warehouse;

    private Table newTable(LocalCatalog catalog) {
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        return catalog.openTable("t");
    }

    private Table newCompositeTable(LocalCatalog catalog) {
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("region", DataType.STRING, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id", "region"));
        return catalog.openTable("t");
    }

    @Test
    void renameSinglePrimaryKeyCommitsAndNewNameWorksForWriteMergeDelete() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b")))); // v1

        // 主键列改名像普通列改名一样正常提交
        Snapshot renamed = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null)))); // v2
        assertEquals(2L, renamed.version());
        assertEquals(List.of("order_id"), renamed.schema().primaryKey());
        // 列 ID 不变：主键值编码不变，改名前后数据无缝衔接
        assertEquals(renamed.schema().columnByName("order_id").id(),
                t.snapshotAt(1).schema().columnByName("id").id());
        LOG.info("[pk-rename:accept] committedVersion={} snapshotId={} pk=[id]->[order_id] columnId={} basis=columnIdStable",
                renamed.version(), renamed.snapshotId(), renamed.schema().columnByName("order_id").id());

        // 用新主键列名继续追加、合并
        WriteResult app = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                List.of(Map.of("order_id", 3, "v", "c")))); // v3
        assertTrue(app.committed());
        WriteResult mer = t.commit(new WriteRequest(WriteMode.MERGE, 3L,
                List.of(Map.of("order_id", 1, "v", "a2")))); // v4
        assertTrue(mer.committed());
        // 用新主键列名按主键删除
        WriteResult del = t.delete(new DeleteRequest(4L, List.of(Map.of("order_id", 2)))); // v5
        assertTrue(del.committed());

        List<Map<String, Object>> head = t.readLatest();
        assertEquals(2, head.size());
        assertTrue(head.stream().allMatch(m -> m.containsKey("order_id") && !m.containsKey("id")));
        assertEquals("a2", head.stream().filter(m -> m.get("order_id").equals(1L))
                .findFirst().orElseThrow().get("v"));
        assertTrue(head.stream().noneMatch(m -> m.get("order_id").equals(2L)));

        // 改名之前的历史版本仍按当时的列名读到原来的值，没有错列
        List<Map<String, Object>> atV1 = t.readAt(1);
        assertEquals(2, atV1.size());
        assertTrue(atV1.stream().allMatch(m -> m.containsKey("id") && !m.containsKey("order_id")));
        assertEquals("a", atV1.stream().filter(m -> m.get("id").equals(1L))
                .findFirst().orElseThrow().get("v"));
        assertEquals("b", atV1.stream().filter(m -> m.get("id").equals(2L))
                .findFirst().orElseThrow().get("v"));
        LOG.info("[pk-rename:visibility] headVersion={} headSnapshotId={} headRows={} v1Rows={} basis=perSnapshotSchema+columnId",
                t.currentSnapshot().version(), t.currentSnapshot().snapshotId(),
                head.size(), atV1.size());
    }

    @Test
    void renameCompositePrimaryKeyMembersKeepsKeysConsistent() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newCompositeTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("id", 1, "region", "us", "v", "a"),
                Map.of("id", 2, "region", "eu", "v", "b")))); // v1

        // 复合主键中的一个成员改名
        Snapshot v2 = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "region", "area", null))));
        assertEquals(List.of("id", "area"), v2.schema().primaryKey());
        // 用新名字删除复合主键 (1, us)
        WriteResult del = t.delete(new DeleteRequest(2L,
                List.of(Map.of("id", 1, "area", "us")))); // v3
        assertTrue(del.committed());
        assertEquals(1, t.readLatest().size());

        // 两个成员都改名（第二次演进），之后完全用新名字写与删
        Snapshot v4 = t.evolve(new SchemaChangeRequest(3L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null))));
        assertEquals(List.of("order_id", "area"), v4.schema().primaryKey());
        WriteResult app = t.commit(new WriteRequest(WriteMode.APPEND, 4L,
                List.of(Map.of("order_id", 3, "area", "ap", "v", "c")))); // v5
        assertTrue(app.committed());
        WriteResult del2 = t.delete(new DeleteRequest(5L,
                List.of(Map.of("order_id", 2, "area", "eu")))); // v6
        assertTrue(del2.committed());

        List<Map<String, Object>> head = t.readLatest();
        assertEquals(1, head.size());
        assertEquals(Map.of("order_id", 3L, "area", "ap", "v", "c"), head.get(0));

        // 历史快照按各自版本的列名复现：v1 用 (id, region)，v3 用 (id, area)
        List<Map<String, Object>> atV1 = t.readAt(1);
        assertEquals(2, atV1.size());
        assertTrue(atV1.stream().allMatch(m -> m.containsKey("id") && m.containsKey("region")));
        List<Map<String, Object>> atV3 = t.readAt(3);
        assertEquals(1, atV3.size());
        assertEquals(Map.of("id", 2L, "area", "eu", "v", "b"), atV3.get(0));
        LOG.info("[pk-rename:composite] v2SnapshotId={} v4SnapshotId={} headVersion={} headRows={} v1Rows={} v3Rows={} basis=pkDefinitionFollowsRename",
                v2.snapshotId(), v4.snapshotId(), t.currentSnapshot().version(),
                head.size(), atV1.size(), atV3.size());
    }

    @Test
    void interleavedDeleteRenameAddDropCompactKeepsEverySnapshotReproducible() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("t",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null),
                        new ColumnSpec("tmp", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("t");

        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("id", 1, "v", "a", "tmp", "x"),
                Map.of("id", 2, "v", "b", "tmp", "y"),
                Map.of("id", 3, "v", "c", "tmp", "z")))); // v1
        // 先按主键删一行
        t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1)))); // v2
        // 再改主键列名
        t.evolve(new SchemaChangeRequest(2L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null)))); // v3
        // 用新名字再删一行
        t.delete(new DeleteRequest(3L, List.of(Map.of("order_id", 2)))); // v4
        // 加列 + 删列与改名穿插
        t.evolve(new SchemaChangeRequest(4L, List.of(
                new ColumnChange(ColumnChange.Kind.ADD, "note", null,
                        new ColumnSpec("note", DataType.STRING, true, null)),
                new ColumnChange(ColumnChange.Kind.DROP, "tmp", null, null)))); // v5
        // 删除之后又写回同一个主键（order_id=1 在 v2 被删）：按既定规则数据行胜出
        WriteResult rewrite = t.commit(new WriteRequest(WriteMode.APPEND, 5L,
                List.of(Map.of("order_id", 1, "v", "a-back", "note", "n1")))); // v6
        assertTrue(rewrite.committed(), "deleted-then-rewritten key must be accepted by APPEND");
        // 改名之后再整理数据文件
        Snapshot compacted = t.compact(); // v7
        assertEquals(1, compacted.files().size());

        // 整理前后结果一致；已删的 order_id=2 不复活；删后重写的 order_id=1 可见
        List<Map<String, Object>> beforeCompact = t.readAt(6);
        List<Map<String, Object>> head = t.readAt(7);
        assertEquals(beforeCompact, head);
        assertEquals(2, head.size());
        assertTrue(head.stream().noneMatch(m -> m.get("order_id").equals(2L)),
                "deleted key must not reappear after compaction");
        assertEquals("a-back", head.stream().filter(m -> m.get("order_id").equals(1L))
                .findFirst().orElseThrow().get("v"));
        assertTrue(head.stream().noneMatch(m -> m.containsKey("tmp")));

        // 每一个历史快照按它自己那一版表结构把值原样读出
        assertEquals(3, t.readAt(1).size());
        assertTrue(t.readAt(1).stream().allMatch(m -> m.containsKey("id") && m.containsKey("tmp")));
        assertEquals(2, t.readAt(2).size());                       // id=1 已删
        assertTrue(t.readAt(2).stream().noneMatch(m -> m.get("id").equals(1L)));
        assertEquals(2, t.readAt(3).size());                       // 改名后列名生效
        assertTrue(t.readAt(3).stream().allMatch(m -> m.containsKey("order_id")));
        assertEquals(1, t.readAt(4).size());                       // order_id=2 已删
        assertEquals(3L, t.readAt(4).get(0).get("order_id"));
        assertTrue(t.readAt(5).stream().noneMatch(m -> m.containsKey("tmp"))); // 删列生效
        assertTrue(t.readAt(5).stream().allMatch(m -> m.containsKey("note"))); // 加列生效
        LOG.info("[pk-rename:interleave] compactedVersion={} snapshotId={} headRows={} v1..v6Rows=[{},{},{},{},{},{}] basis=perSnapshotSchema+tombstoneCoordinates",
                compacted.version(), compacted.snapshotId(), head.size(),
                t.readAt(1).size(), t.readAt(2).size(), t.readAt(3).size(),
                t.readAt(4).size(), t.readAt(5).size(), t.readAt(6).size());
    }

    @Test
    void invalidPrimaryKeyRenamesFailWithDistinctReasonsAndNoSideEffects() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a")))); // v1
        List<Map<String, Object>> before = t.readLatest();
        int historyBefore = t.history().size();

        // 改成已经存在的列名（含改成自己）
        SchemaEvolutionException dup = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "v", null)))));
        assertTrue(dup.getMessage().contains("already exists"));
        SchemaEvolutionException self = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "id", null)))));
        assertTrue(self.getMessage().contains("already exists"));
        // 改一个不存在的列
        SchemaEvolutionException missing = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "nope", "x", null)))));
        assertTrue(missing.getMessage().contains("not found"));
        // 改成空白列名
        SchemaEvolutionException blank = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "  ", null)))));
        assertTrue(blank.getMessage().contains("non-blank"));
        // 改成非法列名（含控制字符，会破坏复合主键编码分隔符）
        SchemaEvolutionException illegal = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "a\u001fb", null)))));
        assertTrue(illegal.getMessage().contains("control character"));
        LOG.warn("[pk-rename:reject] duplicateName / selfRename / columnNotFound / blankName / illegalName rejected with distinct messages; headVersion={} basis=allOrNothing",
                t.currentSnapshot().version());

        // 失败后版本不推进、数据不变、不留半截状态
        assertEquals(1L, t.currentSnapshot().version());
        assertEquals(historyBefore, t.history().size());
        assertEquals(before, t.readLatest());
        assertEquals(List.of("id"), t.currentSnapshot().schema().primaryKey());
    }

    @Test
    void staleExpectedVersionAndConcurrentRaceFollowOptimisticConcurrencyRules() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = newTable(catalog);
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a")))); // v1

        // 改名与写入基于同一基版本并发竞争：恰好一个成功，另一个得到明确冲突
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger evolveWon = new AtomicInteger();
        AtomicInteger commitWon = new AtomicInteger();
        try {
            Future<?> evolveFuture = pool.submit(() -> {
                try {
                    Snapshot s = t.evolve(new SchemaChangeRequest(1L,
                            List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null))));
                    evolveWon.incrementAndGet();
                    LOG.info("[concurrent:accept] kind=evolve committedVersion={} snapshotId={} basis=headCAS(base=1)",
                            s.version(), s.snapshotId());
                } catch (ConflictException e) {
                    LOG.warn("[concurrent:reject] kind=evolve reason=SNAPSHOT_CONFLICT basis=OCC");
                }
            });
            Future<?> commitFuture = pool.submit(() -> {
                try {
                    WriteResult r = t.commit(new WriteRequest(WriteMode.APPEND, 1L,
                            List.of(Map.of("id", 2, "v", "b"))));
                    if (r.committed()) {
                        commitWon.incrementAndGet();
                        LOG.info("[concurrent:accept] kind=commit committedVersion={} snapshotId={} basis=headCAS(base=1)",
                                r.committedVersion(), r.snapshotId());
                    }
                } catch (ConflictException e) {
                    LOG.warn("[concurrent:reject] kind=commit reason=SNAPSHOT_CONFLICT basis=OCC");
                }
            });
            evolveFuture.get();
            commitFuture.get();
        } finally {
            pool.shutdown();
        }
        assertEquals(1, evolveWon.get() + commitWon.get(), "exactly one commit wins per base version");
        assertEquals(2L, t.currentSnapshot().version());

        // 输家按乐观并发规则刷新到最新版本后重试，已提交结果不被覆盖
        if (evolveWon.get() == 1) {
            // 改名已生效：旧列名写入被校验拒绝（未知列），用新名字重试成功
            WriteResult stale = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                    List.of(Map.of("id", 2, "v", "b"))));
            assertFalse(stale.committed());
            WriteResult retried = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                    List.of(Map.of("order_id", 2, "v", "b")))); // v3
            assertTrue(retried.committed());
            assertEquals(2, t.readLatest().size());
        } else {
            // 写入已生效：改名以过期基版本重试得到明确冲突，刷新后重试成功
            assertThrows(ConflictException.class, () -> t.evolve(new SchemaChangeRequest(1L,
                    List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null)))));
            Snapshot retried = t.evolve(new SchemaChangeRequest(2L,
                    List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "order_id", null)))); // v3
            assertEquals(3L, retried.version());
            assertEquals(2, t.readLatest().size());
        }
        // 已提交的 v1/v2 历史不被覆盖，仍按各自 schema 复现
        assertEquals(1, t.readAt(1).size());
        assertTrue(t.readAt(1).stream().allMatch(m -> m.containsKey("id")));
        LOG.info("[concurrent:summary] evolveWon={} commitWon={} headVersion={} headSnapshotId={} basis=OCC+retry",
                evolveWon.get(), commitWon.get(),
                t.currentSnapshot().version(), t.currentSnapshot().snapshotId());
    }
}
