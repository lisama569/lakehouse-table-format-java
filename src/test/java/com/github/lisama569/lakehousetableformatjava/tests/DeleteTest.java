package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RejectReason;
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
 * 按主键批量删除的语义：可见性、历史保留、整批校验的可区分原因、
 * 删除后重新写入的确定性规则，以及与 OCC 的配合。
 */
class DeleteTest {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteTest.class);

    @TempDir
    Path warehouse;

    private Table tableWithFiveRows(LocalCatalog catalog) {
        catalog.createTable("orders",
                List.of(new ColumnSpec("id", DataType.LONG, false, null),
                        new ColumnSpec("v", DataType.STRING, true, null)),
                List.of("id"));
        Table t = catalog.openTable("orders");
        t.commit(new WriteRequest(WriteMode.APPEND, 0L, List.of(
                Map.of("id", 1, "v", "a"),
                Map.of("id", 2, "v", "b"),
                Map.of("id", 3, "v", "c"),
                Map.of("id", 4, "v", "d"),
                Map.of("id", 5, "v", "e")))); // v1
        return t;
    }

    private long idOf(List<Map<String, Object>> rows, long id) {
        return (long) rows.stream().filter(r -> r.get("id").equals(id)).findFirst().orElseThrow().get("id");
    }

    @Test
    void batchDeleteHidesRowsButKeepsHistory() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = tableWithFiveRows(catalog);

        WriteResult r = t.delete(new DeleteRequest(1L, List.of(
                Map.of("id", 2), Map.of("id", 4))));
        assertTrue(r.committed(), () -> "delete should commit: " + r.rejected());
        assertEquals(2L, r.committedVersion());
        List<Long> visible = t.readLatest().stream().map(m -> (Long) m.get("id")).sorted().toList();
        assertEquals(List.of(1L, 3L, 5L), visible);
        LOG.info("[delete:accept] committedVersion={} snapshotId={} deletedKeys=[2,4] visible={} basis=tombstone coordinates win over old rows",
                r.committedVersion(), r.snapshotId(), visible);

        // 删除之前的历史版本原样读得到被删行
        List<Map<String, Object>> atV1 = t.readAt(1);
        assertEquals(5, atV1.size());
        assertEquals("b", atV1.stream().filter(m -> m.get("id").equals(2L)).findFirst().orElseThrow().get("v"));
        assertEquals("d", atV1.stream().filter(m -> m.get("id").equals(4L)).findFirst().orElseThrow().get("v"));
        // 删除后的快照读不到
        assertEquals(0L, t.readAt(2).stream().filter(m -> m.get("id").equals(2L)).count());
        LOG.info("[delete:visibility] history v1 still contains id=2(id=4); head v2 excludes them -> snapshot isolation preserved");

        // 重新打开 catalog 后结论一致（墓碑文件持久化）
        Table reopened = new LocalCatalog(warehouse).openTable("orders");
        assertEquals(List.of(1L, 3L, 5L),
                reopened.readLatest().stream().map(m -> (Long) m.get("id")).sorted().toList());
        assertEquals(5, reopened.readAt(1).size());
    }

    @Test
    void rejectReasonsAreDistinctAndBatchIsAllOrNothing() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = tableWithFiveRows(catalog);

        // 同一批里：缺主键、重复主键、不存在的主键、混入非主键列、类型不符
        WriteResult r = t.delete(new DeleteRequest(1L, List.of(
                Map.of(),                                        // 0 MISSING_PRIMARY_KEY
                Map.of("id", 1),                                 // 1 合法
                Map.of("id", 1),                                 // 2 DUPLICATE（与 1）
                Map.of("id", 99),                                // 3 NOT_FOUND
                Map.of("id", 7, "v", "x"),                       // 4 UNKNOWN_COLUMN（非主键列）
                Map.of("id", "nope"),                            // 5 TYPE_MISMATCH
                Map.of("id", 3)                                  // 6 合法
        )));
        assertFalse(r.committed());
        assertEquals(5, r.rejected().size(), "two valid keys, five rejected");
        assertEquals(java.util.EnumSet.of(
                        RejectReason.DUPLICATE_PRIMARY_KEY_IN_BATCH,
                        RejectReason.MISSING_PRIMARY_KEY,
                        RejectReason.PRIMARY_KEY_NOT_FOUND,
                        RejectReason.TYPE_MISMATCH,
                        RejectReason.UNKNOWN_COLUMN),
                java.util.EnumSet.copyOf(r.rejected().stream().map(rr -> rr.reason())
                        .collect(java.util.stream.Collectors.toSet())));
        r.rejected().forEach(rr -> LOG.info("[delete:reject] batchIndex={} key={} reason={} msg={}",
                rr.batchIndex(), rr.primaryKeyValue(), rr.reason(), rr.message()));

        // 整批无副作用：头版本仍是 1，5 行都在
        assertEquals(1L, t.currentSnapshot().version());
        assertEquals(5, t.readLatest().size());
        idOf(t.readLatest(), 1);
        idOf(t.readLatest(), 3);
        LOG.info("[delete:all-or-nothing] version still=1 snapshotId={} rowCount=5 basis=no snapshot/file written when any key invalid",
                t.currentSnapshot().snapshotId());

        // 剔除问题键后整批可以提交
        WriteResult retry = t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1), Map.of("id", 3))));
        assertTrue(retry.committed(), () -> retry.rejected().toString());
        assertEquals(3, t.readLatest().size());
    }

    @Test
    void deletingAlreadyDeletedKeyIsNotFound() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = tableWithFiveRows(catalog);
        WriteResult first = t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1))));
        assertTrue(first.committed());
        // 再次删除同一个键：当前快照已不存在 -> PRIMARY_KEY_NOT_FOUND，不静默成功
        WriteResult second = t.delete(new DeleteRequest(2L, List.of(Map.of("id", 1))));
        assertFalse(second.committed());
        assertEquals(RejectReason.PRIMARY_KEY_NOT_FOUND, second.rejected().get(0).reason());
        LOG.info("[delete:not-found] key=1 already deleted at v2 -> rejected at v3 reason=PRIMARY_KEY_NOT_FOUND snapshotId={}",
                t.currentSnapshot().snapshotId());
    }

    @Test
    void reinsertAfterDeleteHasStableDeterministicOutcome() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = tableWithFiveRows(catalog);
        t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1))));          // v2 删除
        // 规则：墓碑只遮蔽“坐标不大于它”的行；删除之后新写入的行坐标更大，确定胜出
        WriteResult reAppend = t.commit(new WriteRequest(WriteMode.APPEND, 2L,
                List.of(Map.of("id", 1, "v", "A2"))));                     // v3 重新插入
        assertEquals(3L, reAppend.committedVersion());
        List<Map<String, Object>> head = t.readLatest();
        assertEquals(5, head.size());
        assertEquals("A2", head.stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));

        // 再删再插，结果仍然稳定
        assertTrue(t.delete(new DeleteRequest(3L, List.of(Map.of("id", 1)))).committed()); // v4
        assertEquals(4, t.readLatest().size());
        t.commit(new WriteRequest(WriteMode.MERGE, 4L,
                List.of(Map.of("id", 1, "v", "A3"))));                     // v5
        assertEquals("A3", t.readLatest().stream().filter(m -> m.get("id").equals(1L))
                .findFirst().orElseThrow().get("v"));
        // 每个历史快照各自的结论：v1 有 a，v2 无，v3 有 A2，v4 无，v5 有 A3
        assertEquals("a", t.readAt(1).stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
        assertEquals(0L, t.readAt(2).stream().filter(m -> m.get("id").equals(1L)).count());
        assertEquals("A2", t.readAt(3).stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
        assertEquals(0L, t.readAt(4).stream().filter(m -> m.get("id").equals(1L)).count());
        assertEquals("A3", t.readAt(5).stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow().get("v"));
        LOG.info("[delete:reinsert] timeline v1=a v2=<deleted> v3=A2 v4=<deleted> v5=A3, deterministic by (commitVersion,seq)");
    }

    @Test
    void staleDeleteConflictsAndCanRetry() {
        LocalCatalog catalog = new LocalCatalog(warehouse);
        Table t = tableWithFiveRows(catalog);
        // 头已在 v1，却基于 v0 删除 -> OCC 冲突，不做任何删除
        ConflictException ex = assertThrows(ConflictException.class,
                () -> t.delete(new DeleteRequest(0L, List.of(Map.of("id", 1)))));
        assertEquals(0L, ex.expectedVersion());
        assertEquals(1L, ex.currentVersion());
        assertEquals(5, t.readLatest().size());
        LOG.warn("[delete:reject] OCC expectedVersion=0 currentVersion=1 basis=head mismatch; nothing deleted");

        WriteResult ok = t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1))));
        assertTrue(ok.committed());
        assertEquals(4, t.readLatest().size());
        LOG.info("[delete:retry] based on refreshed v1 -> committedVersion={} snapshotId={}",
                ok.committedVersion(), ok.snapshotId());
    }
}
