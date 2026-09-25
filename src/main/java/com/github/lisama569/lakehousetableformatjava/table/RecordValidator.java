package com.github.lisama569.lakehousetableformatjava.table;

import com.github.lisama569.lakehousetableformatjava.model.Column;
import com.github.lisama569.lakehousetableformatjava.model.RejectReason;
import com.github.lisama569.lakehousetableformatjava.model.RejectedRecord;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把以列名为键的用户记录校验并转换为以列 ID 为键的内部行。
 *
 * <p>校验顺序是确定的（对每个字段）：未知列 → 主键缺失 → 类型不匹配 →
 * 必填列缺失。每条被拒绝记录至少给出一个原因；主键缺失时同时收集所有缺失的
 * 主键列，便于调用方定位。
 */
final class RecordValidator {

    record Validated(List<Row> rows, List<RejectedRecord> rejected) {
        boolean ok() {
            return rejected.isEmpty();
        }
    }

    private record Outcome(Row row, RejectedRecord rejected) {
    }

    private RecordValidator() {
    }

    /**
     * @param schema   当前（头快照）schema
     * @param records  批次记录，以列名为键
     * @param existing 表中已存在的主键值集合（逻辑键字符串）
     * @param mode     APPEND 时命中 existing 会拒绝；MERGE 不检查
     */
    static Validated validate(Schema schema,
                              List<Map<String, Object>> records,
                              Set<String> existing,
                              com.github.lisama569.lakehousetableformatjava.model.WriteMode mode) {
        List<Row> rows = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        // 批内重复主键：以首次出现为准记录索引，确定地归因为“批内重复”
        Set<String> seenInBatch = new HashSet<>();

        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            Outcome outcome = validateOne(schema, i, record, existing, seenInBatch, mode);
            if (outcome.rejected() != null) {
                rejected.add(outcome.rejected());
            } else {
                rows.add(outcome.row());
            }
        }
        return new Validated(rows, rejected);
    }

    private static Outcome validateOne(Schema schema,
                                       int index,
                                       Map<String, Object> raw,
                                       Set<String> existing,
                                       Set<String> seenInBatch,
                                       com.github.lisama569.lakehousetableformatjava.model.WriteMode mode) {
        Map<Integer, Object> values = new LinkedHashMap<>();
        String primaryKeyValue = null;

        if (raw == null) {
            return reject(index, null, RejectReason.MISSING_PRIMARY_KEY,
                    "record is null", raw);
        }

        // 1) 未知列：出现 schema 中不存在的列名即拒绝，不静默忽略
        for (String key : raw.keySet()) {
            if (schema.columnByName(key) == null) {
                return reject(index, pkDisplay(raw, schema), RejectReason.UNKNOWN_COLUMN,
                        "unknown column '" + key + "' for schema v" + schema.version(), raw);
            }
        }

        // 2) 主键：必须全部提供且非空白字符串/非 null
        List<String> missingPk = new ArrayList<>();
        List<Object> pkParts = new ArrayList<>();
        for (String pkName : schema.primaryKey()) {
            Column col = schema.columnByName(pkName);
            Object v = raw.get(pkName);
            if (v == null || (v instanceof String s && s.isEmpty())) {
                missingPk.add(pkName);
                continue;
            }
            try {
                pkParts.add(col.type().coerce(v));
            } catch (IllegalArgumentException e) {
                return reject(index, null, RejectReason.TYPE_MISMATCH,
                        "primary key column '" + pkName + "': " + e.getMessage(), raw);
            }
        }
        if (!missingPk.isEmpty()) {
            return reject(index, pkDisplay(raw, schema), RejectReason.MISSING_PRIMARY_KEY,
                    "missing or empty primary key columns: " + missingPk, raw);
        }
        primaryKeyValue = KeyCodec.encode(pkParts);
        if (primaryKeyValue.isBlank()) {
            return reject(index, primaryKeyValue, RejectReason.MISSING_PRIMARY_KEY,
                    "blank primary key", raw);
        }

        // 3) 批内重复主键（确定性拒绝，不静默丢弃后写记录）
        if (!seenInBatch.add(primaryKeyValue)) {
            return reject(index, primaryKeyValue, RejectReason.DUPLICATE_PRIMARY_KEY_IN_BATCH,
                    "duplicate primary key within the same batch", raw);
        }

        // 4) APPEND 不允许覆盖表中已有主键；MERGE 允许
        if (mode == com.github.lisama569.lakehousetableformatjava.model.WriteMode.APPEND
                && existing.contains(primaryKeyValue)) {
            return reject(index, primaryKeyValue, RejectReason.PRIMARY_KEY_ALREADY_EXISTS,
                    "primary key already exists and APPEND does not overwrite", raw);
        }

        // 5) 非主键字段：类型校验；缺失列按 nullable / defaultValue 规则补值
        for (Column col : schema.columns()) {
            if (schema.primaryKey().contains(col.name())) {
                values.put(col.id(), coercedValue(col, raw.get(col.name())));
                continue;
            }
            Object v = raw.get(col.name());
            if (!raw.containsKey(col.name()) || v == null) {
                if (v == null && raw.containsKey(col.name())) {
                    values.put(col.id(), null); // 显式 null
                    continue;
                }
                if (col.defaultValue() != null) {
                    values.put(col.id(), coerceDefault(col));
                } else if (col.nullable()) {
                    values.put(col.id(), null); // 稳定缺省语义：可空列缺省为 null
                } else {
                    return reject(index, primaryKeyValue, RejectReason.MISSING_REQUIRED_COLUMN,
                            "missing value for non-nullable column '" + col.name()
                                    + "' without default value", raw);
                }
                continue;
            }
            try {
                values.put(col.id(), col.type().coerce(v));
            } catch (IllegalArgumentException e) {
                return reject(index, primaryKeyValue, RejectReason.TYPE_MISMATCH,
                        "column '" + col.name() + "': " + e.getMessage(), raw);
            }
        }

        return new Outcome(new Row(values), null);
    }

    private static Object coercedValue(Column col, Object v) {
        return col.type().coerce(v);
    }

    private static Object coerceDefault(Column col) {
        return col.type().parseDefault(col.defaultValue());
    }

    private static String pkDisplay(Map<String, Object> raw, Schema schema) {
        if (raw == null) {
            return null;
        }
        List<Object> parts = new ArrayList<>();
        for (String pkName : schema.primaryKey()) {
            parts.add(raw.get(pkName));
        }
        return KeyCodec.encode(parts);
    }

    private static Outcome reject(int index, String pk, RejectReason reason, String message,
                                  Map<String, Object> raw) {
        return new Outcome(null, new RejectedRecord(index, pk, reason, message, raw));
    }
}
