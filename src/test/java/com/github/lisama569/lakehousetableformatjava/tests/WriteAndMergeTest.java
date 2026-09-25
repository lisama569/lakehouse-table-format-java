package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 追加/合并写入：批内去重确定性、更新合并、以及可区分的拒绝原因。 */
class WriteAndMergeTest {

    private static Map<String, Object> row(long id, String name, Object score) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("name", name);
        r.put("score", score);
        return r;
    }

    @Test
    void duplicateKeysInBatch_lastOneWinsDeterministically(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        var report = t.write(0, WriteMode.APPEND, List.of(
                row(1, "first", 1.0),
                row(1, "second", 2.0),   // 同批次重复主键
                row(2, "keep", 9.0)));

        assertEquals(1L, report.duplicatesInBatch());
        assertEquals(2L, report.rowsAccepted());
        var rows = t.readLatest();
        assertEquals("second", rows.get("1").get("name"));
        assertEquals(2.0, rows.get("1").get("score"));
        System.out.printf("[TEST] 批内去重 -> 提交版本 v%d 快照 %s 判定依据: %s%n",
                report.version(), report.snapshotId(), report.basis());
    }

    @Test
    void mergeUpdatesExistingRowAndKeepsUnmentionedColumnsSemantics(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "alice", 10.0)));
        // MERGE 更新已存在记录：整行替换（新文件高 seq 覆盖旧文件），未提及列按缺省值处理
        var report = t.write(1, WriteMode.MERGE, List.of(
                basicRow(1, "alice2"),
                basicRow(2, "bob")));
        var rows = t.readLatest();
        assertEquals(2, rows.size());
        assertEquals("alice2", rows.get("1").get("name"));
        assertEquals(0.0, rows.get("1").get("score")); // 未显式提供 -> 列缺省值
        assertEquals("bob", rows.get("2").get("name"));
        System.out.printf("[TEST] MERGE -> 提交版本 v%d 快照 %s%n", report.version(), report.snapshotId());
    }

    private Map<String, Object> basicRow(long id, String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("name", name);
        return r;
    }

    @Test
    void appendRejectsExistingPrimaryKeyWithDistinctReason(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        t.write(0, WriteMode.APPEND, List.of(row(1, "a", 1.0)));
        CommitRejectedException ex = assertThrows(CommitRejectedException.class,
                () -> t.write(1, WriteMode.APPEND, List.of(row(1, "b", 2.0))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.PRIMARY_KEY_ALREADY_EXISTS,
                ex.getReason());
        assertEquals(1, ex.getRowRejections().size());
        System.out.printf("[TEST] APPEND 冲突被拒绝，原因=%s 明细=%s%n",
                ex.getReason(), ex.getRowRejections().get(0));
    }

    @Test
    void invalidRowsAreRejectedWithDistinguishableReasons(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");

        // 1) 缺主键
        var e1 = assertThrows(CommitRejectedException.class, () -> t.write(0, WriteMode.APPEND,
                List.of(row(1, "ok", 1.0), Map.of("name", "no-id"))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.MISSING_PRIMARY_KEY,
                e1.getReason());
        assertTrue(e1.getRowRejections().stream().anyMatch(r -> r.reason()
                == com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.MISSING_PRIMARY_KEY));

        // 2) 类型不匹配：id 给字符串、score 给布尔
        var e2 = assertThrows(CommitRejectedException.class, () -> t.write(0, WriteMode.APPEND,
                List.of(Map.of("id", "not-a-number", "name", "x"))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.TYPE_MISMATCH,
                e2.getReason());

        var e3 = assertThrows(CommitRejectedException.class, () -> t.write(0, WriteMode.APPEND,
                List.of(row(3, "x", true))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.TYPE_MISMATCH,
                e3.getReason());

        // 3) 未知列
        var e4 = assertThrows(CommitRejectedException.class, () -> t.write(0, WriteMode.APPEND,
                List.of(Map.of("id", 1, "name", "x", "ghost", "v"))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.UNKNOWN_COLUMN,
                e4.getReason());

        // 4) 缺必填列（name 非空且无缺省值）
        var e5 = assertThrows(CommitRejectedException.class, () -> t.write(0, WriteMode.APPEND,
                List.of(Map.of("id", 5))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.MISSING_REQUIRED_COLUMN,
                e5.getReason());

        // 5) 任一记录非法 -> 整批不写入
        assertEquals(0, t.readLatest().size());

        System.out.printf("[TEST] 拒绝原因可区分: MISSING_PRIMARY_KEY / TYPE_MISMATCH / UNKNOWN_COLUMN / MISSING_REQUIRED_COLUMN%n");
    }

    @Test
    void lossyNumericConversionIsTypeMismatch(@TempDir Path dir) {
        Table t = LakehouseTestSupport.exampleTable(LakehouseTestSupport.catalog(dir), "t");
        // LONG 主键列收到 1.5：无法无损转为整数 -> TYPE_MISMATCH，而不是静默截断
        var ex = assertThrows(CommitRejectedException.class, () -> t.write(0, WriteMode.APPEND,
                List.of(new LinkedHashMap<>(Map.of("id", 1.5, "name", "x")))));
        assertEquals(com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason.TYPE_MISMATCH,
                ex.getReason());
        // LONG 列收到无小数浮点 1.0 则可无损接受
        var ok = t.write(0, WriteMode.APPEND,
                List.of(new LinkedHashMap<>(Map.of("id", 1.0, "name", "x"))));
        assertEquals(1L, ok.rowsAccepted());
        System.out.printf("[TEST] 1.5->LONG 被拒绝(TYPE_MISMATCH)；1.0->LONG 无损接受，提交 v%d %s%n",
                ok.version(), ok.snapshotId());
    }
}
