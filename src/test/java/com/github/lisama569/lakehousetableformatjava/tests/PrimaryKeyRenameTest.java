package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.ColumnChange;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.exception.SchemaEvolutionException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主键列改名（单列 / 复合主键）及其与删行、加列、删列、整理、并发提交穿插的
 * 自动化覆盖。日志中打印提交版本、快照标识与判定依据。
 */
class PrimaryKeyRenameTest {

    private static final Logger LOG = LoggerFactory.getLogger(PrimaryKeyRenameTest.class);

    @TempDir
    Path warehouse;

    @Test
    void singlePrimaryKeyRenameSupportsWriteMergeDeleteAndKeepsHistory() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("orders",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("orders");

        // v1：用旧主键列名 id 写入
        WriteResult w1 = t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a"), Map.of("id", 2, "v", "b"))));
        assertTrue(w1.committed());

        // v2：主键列 id -> orderId，与普通列改名一样正常提交
        Snapshot renamed = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "orderId", null))));
        assertEquals(2, renamed.version());
        assertEquals(List.of("orderId"), renamed.schema().primaryKey());
        // 列 ID 不变（id 建表时为 1），主键身份仍是同一列
        assertEquals(1, renamed.schema().columnByName("orderId").id());
        LOG.info("[pk-rename:accept] id->orderId columnId=1 committedVersion={} snapshotId={} primaryKey={} basis=rename keeps columnId",
                renamed.version(), renamed.snapshotId(), renamed.schema().primaryKey());

        // 历史 v1 仍按当时的列名读出原值
        Map<String, Object> oldRow = t.readAt(1).get(0);
        assertEquals(1L, oldRow.get("id"));
        assertFalse(oldRow.containsKey("orderId"));

        // 改名后用新主键名追加 v3：不能被当成“主键已存在”，也不能 UNKNOWN_COLUMN
        WriteResult append = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                List.of(Map.of("orderId", 3, "v", "c"))));
        assertTrue(append.committed());
        assertEquals(3L, append.committedVersion());

        // 改名后用新主键名 MERGE v4 覆盖已有主键
        WriteResult merge = t.commit(new WriteRequest(WriteMode.MERGE, 3L,
                List.of(Map.of("orderId", 1, "v", "a2"))));
        assertTrue(merge.committed());
        Map<String, Object> mergedRow = t.readLatest().stream()
                .filter(m -> m.get("orderId").equals(1L)).findFirst().orElseThrow();
        assertEquals("a2", mergedRow.get("v"));

        // 改名后用新主键名按主键删除 v5
        WriteResult del = t.delete(new DeleteRequest(4L, List.of(Map.of("orderId", 2))));
        assertTrue(del.committed());
        assertEquals(5L, del.committedVersion());
        List<Map<String, Object>> head = t.readLatest();
        assertEquals(2, head.size());
        assertFalse(head.stream().anyMatch(m -> m.get("orderId").equals(2L)));
        LOG.info("[pk-rename:use] appendV=3 mergeV=4 deleteV=5 headSnapshotId={} visibleKeys={} basis=all keyed by new name orderId (columnId=1)",
                t.currentSnapshot().snapshotId(),
                head.stream().map(m -> m.get("orderId")).sorted().toList());

        // 改名之后的当前快照里旧名不应再出现；v1 历史仍按旧名读到原始值，且不会错列
        assertFalse(head.get(0).containsKey("id"));
        assertEquals("a", t.readAt(1).stream()
                .filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
    }

    @Test
    void compositePrimaryKeyRenameKeepsKeyPartsAlignedAndNoValueMisread() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("events",
                List.of(new ColumnSpec("tenant", DataType.LONG, false, null),
                        new ColumnSpec("seq", DataType.LONG, false, null),
                        new ColumnSpec("note", DataType.STRING, true, null)),
                List.of("tenant", "seq"));
        Table t = catalog.openTable("events");

        // v1：复合主键 (tenant, seq) 写两行，故意让两列的值不同以便发现错列
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("tenant", 10, "seq", 100, "note", "x"),
                Map.of("tenant", 20, "seq", 200, "note", "y"))));

        // v2：只改复合主键里的 seq -> eventSeq（tenant 不动）
        Snapshot renamed = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "seq", "eventSeq", null))));
        assertEquals(List.of("tenant", "eventSeq"), renamed.schema().primaryKey());
        // 列 ID：tenant=1, seq=2，改名后 ID 仍是 2
        assertEquals(2, renamed.schema().columnByName("eventSeq").id());
        assertEquals(1, renamed.schema().columnByName("tenant").id());
        LOG.info("[pk-rename:composite] seq->eventSeq columnId=2 committedVersion={} snapshotId={} primaryKey={}",
                renamed.version(), renamed.snapshotId(), renamed.schema().primaryKey());

        // 用新复合主键追加、删除
        WriteResult append = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                List.of(Map.of("tenant", 10, "eventSeq", 300, "note", "z"))));
        assertTrue(append.committed());
        // 相同 tenant 但 eventSeq 不同：是不同主键，APPEND 不得判“已存在”
        assertEquals(3, t.readLatest().size());

        WriteResult del = t.delete(new DeleteRequest(3L,
                List.of(Map.of("tenant", 20, "eventSeq", 200))));
        assertTrue(del.committed());
        List<Map<String, Object>> head = t.readLatest();
        assertEquals(2, head.size());
        assertFalse(head.stream().anyMatch(m -> m.get("tenant").equals(20L)));

        // 关键不错列断言：每个快照都按自己那一版 schema 读，主键两列值不能串行
        List<Map<String, Object>> r1 = t.readAt(1);
        Map<String, Object> v1row = r1.stream().filter(m -> m.get("tenant").equals(10L))
                .findFirst().orElseThrow();
        assertEquals(100L, v1row.get("seq"));       // 旧名 seq 读到的是原 seq 列（列 ID 2）
        assertFalse(v1row.containsKey("eventSeq"));

        Map<String, Object> headRow = head.stream().filter(m -> m.get("tenant").equals(10L))
                .findFirst().orElseThrow();
        assertEquals(100L, headRow.get("eventSeq")); // 新名读到同一列 ID 的原值
        assertFalse(headRow.containsKey("seq"));
        assertEquals(10L, headRow.get("tenant"));    // 未改名的主键列保持原值
        LOG.info("[pk-rename:composite] headSnapshotId={} rows={} basis=key parts addressed by stable columnId {1=tenant,2=eventSeq}",
                t.currentSnapshot().snapshotId(), head.size());

        // 删除不存在的完整复合键：按新名字判定 PRIMARY_KEY_NOT_FOUND（整批拒绝）
        WriteResult missing = t.delete(new DeleteRequest(4L,
                List.of(Map.of("tenant", 20, "eventSeq", 200))));
        assertFalse(missing.committed());
        assertEquals(4L, t.currentSnapshot().version());
    }

    @Test
    void pkRenameInterleavedWithDeleteAddDropAndCompactionReproducesEverySnapshot() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("items",
                List.of(new ColumnSpec("sku", DataType.LONG, false, null),
                        new ColumnSpec("name", DataType.STRING, true, null)),
                List.of("sku"));
        Table t = catalog.openTable("items");

        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("sku", 1, "name", "a"),
                Map.of("sku", 2, "name", "b"),
                Map.of("sku", 3, "name", "c")))); // v1
        t.delete(new DeleteRequest(1L, List.of(Map.of("sku", 1))));                 // v2 先删一行
        t.evolve(new SchemaChangeRequest(2L, List.of(
                new ColumnChange(ColumnChange.Kind.RENAME, "sku", "itemSku", null)))); // v3 改主键名
        t.delete(new DeleteRequest(3L, List.of(Map.of("itemSku", 2))));             // v4 新名再删
        WriteResult rewritten = t.commit(new WriteRequest(WriteMode.APPEND, 4L,
                List.of(Map.of("itemSku", 1, "name", "a-reborn"))));               // v5 删后重写同主键
        assertTrue(rewritten.committed());
        t.evolve(new SchemaChangeRequest(5L, List.of(
                new ColumnChange(ColumnChange.Kind.ADD, "tag", null,
                        new ColumnSpec("tag", DataType.STRING, true, "none")))));   // v6 加列
        Snapshot compacted = t.compact();                                          // v7 整理
        t.evolve(new SchemaChangeRequest(7L, List.of(
                new ColumnChange(ColumnChange.Kind.DROP, "tag", null, null))));     // v8 删列

        // 逐快照按它自己的 schema 复现
        assertEquals(3, t.readAt(1).size());                       // v1：3 行，旧主键名 sku
        assertEquals(2, t.readAt(2).size());                       // v2：sku=1 已删
        assertEquals(2, t.readAt(3).size());                       // v3：改名不改变可见行
        assertEquals("c", t.readAt(3).stream()
                .filter(m -> m.get("itemSku").equals(3L)).findFirst().orElseThrow().get("name"));
        assertEquals(1, t.readAt(4).size());                       // v4：itemSku=2 已删
        assertEquals(3L, t.readAt(4).get(0).get("itemSku"));
        assertEquals(2, t.readAt(5).size());                       // v5：同主键重写回来
        assertEquals("a-reborn", t.readAt(5).stream()
                .filter(m -> m.get("itemSku").equals(1L)).findFirst().orElseThrow().get("name"));
        assertEquals("none", t.readAt(6).get(0).get("tag"));       // v6：新列缺省值
        assertEquals(1, compacted.files().size());                 // v7：压成单文件
        assertEquals(2, t.readAt(7).size());                       // 整理后已删行不复活
        assertFalse(t.readAt(7).stream().anyMatch(m -> m.get("itemSku").equals(2L)));
        assertEquals(2, t.readLatest().size());                    // v8（头）
        assertFalse(t.readLatest().get(0).containsKey("tag"));
        assertFalse(t.readLatest().get(0).containsKey("sku"));
        assertTrue(t.readLatest().get(0).containsKey("itemSku"));

        for (Snapshot s : t.history()) {
            LOG.info("[pk-rename:interleave] version={} snapshotId={} op={} pk={} columns={} rows={}",
                    s.version(), s.snapshotId(), s.operation(), s.schema().primaryKey(),
                    s.schema().columns().stream().map(c -> c.id() + ":" + c.name()).toList(),
                    t.readRows(s).size());
        }
        LOG.info("[pk-rename:interleave] compactVersion=7 files=1 deletedKeysAbsent=true basis=tombstone merge rule + columnId addressing");
    }

    @Test
    void invalidPkRenamesFailWithDistinctReasonsAndNoSideEffects() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("p",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("p");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 1, "v", "a")))); // v1

        // 1) 改成已存在的列名
        SchemaEvolutionException dup = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "v", null)))));
        assertTrue(dup.getMessage().contains("already exists"), dup.getMessage());

        // 2) 改一个不存在的列
        SchemaEvolutionException missing = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "ghost", "x", null)))));
        assertTrue(missing.getMessage().contains("column not found"), missing.getMessage());

        // 3) 改成空白名
        SchemaEvolutionException blank = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "  ", null)))));
        assertTrue(blank.getMessage().contains("null or blank"), blank.getMessage());

        // 4) 改成非法名（带控制字符 / 两端空白），原因与“空白”可区分
        SchemaEvolutionException illegal = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", " id2", null)))));
        assertTrue(illegal.getMessage().contains("control characters")
                || illegal.getMessage().contains("whitespace"), illegal.getMessage());

        // 任何失败后：版本不推进、schema 与数据不变、无半截状态
        assertEquals(1L, t.currentSnapshot().version());
        assertEquals(List.of("id"), t.currentSnapshot().schema().primaryKey());
        assertEquals(1, t.readLatest().size());
        assertEquals(1L, t.readLatest().get(0).get("id"));
        LOG.warn("[pk-rename:reject] distinct reasons=[already exists, column not found, null or blank, illegal chars]; head stays v1, no partial state");

        // 主键列依旧禁止 DROP（改名前与改名后都禁止）
        assertThrows(SchemaEvolutionException.class, () -> t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.DROP, "id", null, null)))));
        t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "pk", null)))); // v2
        // 同批先改名再 DROP 主键列：按列 ID 仍判定为主键，拒绝
        SchemaEvolutionException dropRenamed = assertThrows(SchemaEvolutionException.class,
                () -> t.evolve(new SchemaChangeRequest(2L, List.of(
                        new ColumnChange(ColumnChange.Kind.RENAME, "pk", "pk2", null),
                        new ColumnChange(ColumnChange.Kind.DROP, "pk2", null, null)))));
        assertTrue(dropRenamed.getMessage().contains("primary key"), dropRenamed.getMessage());
        assertEquals(2L, t.currentSnapshot().version());
    }

    @Test
    void concurrentPkRenamesFollowOccExactlyOneWinsAndRefreshedRetryLands() throws Exception {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("c",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("c");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(Map.of("id", 1, "v", "a")))); // v1

        // 两个改名提交都基于 v1：id->renamedById 与 id->otherName，恰一个成功
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        futures.add(pool.submit(() -> {
            try {
                start.await();
                Snapshot s = t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "renamedById", null))));
                accepted.incrementAndGet();
                LOG.info("[pk-rename:concurrent:accept] winner=id->renamedById committedVersion={} snapshotId={} basis=headCAS(base=1)",
                        s.version(), s.snapshotId());
            } catch (com.github.lisama569.lakehousetableformatjava.exception.ConflictException e) {
                conflicts.incrementAndGet();
                LOG.warn("[pk-rename:concurrent:reject] loser=id->renamedById reason=SNAPSHOT_CONFLICT expectedVersion=1 currentVersion={} basis=OCC",
                        e.currentVersion());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        futures.add(pool.submit(() -> {
            try {
                start.await();
                Snapshot s = t.evolve(new SchemaChangeRequest(1L,
                        List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "otherName", null))));
                accepted.incrementAndGet();
                LOG.info("[pk-rename:concurrent:accept] winner=id->otherName committedVersion={} snapshotId={} basis=headCAS(base=1)",
                        s.version(), s.snapshotId());
            } catch (com.github.lisama569.lakehousetableformatjava.exception.ConflictException e) {
                conflicts.incrementAndGet();
                LOG.warn("[pk-rename:concurrent:reject] loser=id->otherName reason=SNAPSHOT_CONFLICT expectedVersion=1 currentVersion={} basis=OCC",
                        e.currentVersion());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, accepted.get(), "exactly one stale-base rename may win");
        assertEquals(1, conflicts.get(), "the loser must get SNAPSHOT_CONFLICT");
        assertEquals(2L, t.currentSnapshot().version());
        // 已提交结果不被覆盖：头 schema 的主键名就是赢家选的名字
        String headPk = t.currentSnapshot().schema().primaryKey().get(0);
        assertTrue(headPk.equals("renamedById") || headPk.equals("otherName"));

        // 输家刷新到 v2 后重试：在赢家的新列名上再改名，正常落地为 v3
        String finalName = headPk + "2";
        Snapshot retried = t.evolve(new SchemaChangeRequest(2L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, headPk, finalName, null))));
        assertEquals(3L, retried.version());
        assertEquals(List.of(finalName), retried.schema().primaryKey());
        // 用最终新主键名仍可正常写入与按主键删除
        assertTrue(t.commit(new WriteRequest(WriteMode.APPEND, 3L,
                List.of(Map.of(finalName, 2, "v", "b")))).committed());
        WriteResult del = t.delete(new DeleteRequest(4L, List.of(Map.of(finalName, 1))));
        assertTrue(del.committed());
        assertEquals(1, t.readLatest().size());
        LOG.info("[pk-rename:concurrent:retry] refreshed base=2 rename {}->{} committedVersion=3; write/delete by final name OK headSnapshotId={}",
                headPk, finalName, t.currentSnapshot().snapshotId());
    }

    @Test
    void renamedPrimaryKeySurvivesCatalogReopenAndHistoryRemainsReproducible() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        catalog.createTable("persisted",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("persisted");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L,
                List.of(Map.of("id", 7, "v", "keep")))); // v1
        Snapshot renamed = t.evolve(new SchemaChangeRequest(1L,
                List.of(new ColumnChange(ColumnChange.Kind.RENAME, "id", "pid", null)))); // v2
        String snapshotId = renamed.snapshotId();

        // 全新 catalog 仅从磁盘恢复：快照内嵌的 schema 已带新主键名
        Table reopened = new LocalCatalog(warehouse).openTable("persisted");
        assertEquals(snapshotId, reopened.currentSnapshot().snapshotId());
        assertEquals(List.of("pid"), reopened.currentSnapshot().schema().primaryKey());
        // 头快照用新主键名读到原值
        assertEquals(7L, reopened.readLatest().get(0).get("pid"));
        // 历史 v1 仍按旧名复现
        assertEquals(7L, reopened.readAt(1).get(0).get("id"));
        // 重开后用新主键名删除与追加均正常
        WriteResult del = reopened.delete(new DeleteRequest(2L, List.of(Map.of("pid", 7))));
        assertTrue(del.committed());
        assertEquals(0, reopened.readLatest().size());
        WriteResult re = reopened.commit(new WriteRequest(WriteMode.APPEND, 3L,
                List.of(Map.of("pid", 7, "v", "rewritten"))));
        assertTrue(re.committed());
        assertEquals("rewritten", reopened.readLatest().get(0).get("v"));
        LOG.info("[pk-rename:reopen] snapshotId={} primaryKey after reopen=[pid]; delete+rewrite OK basis=snapshot embeds renamed pk schema",
                snapshotId);
    }
}
