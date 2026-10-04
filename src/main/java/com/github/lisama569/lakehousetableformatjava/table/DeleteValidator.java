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
 * 删除批次的校验：把以列名为键的主键记录转换为删除墓碑行。
 *
 * <p>与写入校验同样遵循 all-or-nothing，且拒绝原因可区分：
 * 主键缺失 → {@code MISSING_PRIMARY_KEY}；主键类型不符 → {@code TYPE_MISMATCH}；
 * 出现未知列 → {@code UNKNOWN_COLUMN}；批内重复主键 →
 * {@code DUPLICATE_PRIMARY_KEY_IN_BATCH}；主键在当前快照不存在 →
 * {@code PRIMARY_KEY_NOT_FOUND}。任何一条非法都会让整批不产生任何副作用。
 */
final class DeleteValidator {

    record Validated(List<Row> tombstones, List<RejectedRecord> rejected) {
        boolean ok() {
            return rejected.isEmpty();
        }
    }

    private DeleteValidator() {
    }

    static Validated validate(Schema schema,
                              List<Map<String, Object>> keys,
                              Set<String> existing) {
        List<Row> tombstones = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();

        for (int i = 0; i < keys.size(); i++) {
            Map<String, Object> raw = keys.get(i);
            if (raw == null) {
                rejected.add(new RejectedRecord(i, null, RejectReason.MISSING_PRIMARY_KEY,
                        "delete key record is null", null));
                continue;
            }
            // 未知列：不静默忽略
            String unknown = null;
            for (String key : raw.keySet()) {
                if (schema.columnByName(key) == null) {
                    unknown = key;
                    break;
                }
            }
            if (unknown != null) {
                rejected.add(new RejectedRecord(i, pkDisplay(raw, schema),
                        RejectReason.UNKNOWN_COLUMN,
                        "unknown column '" + unknown + "' for schema v" + schema.version(), raw));
                continue;
            }
            // 主键：必须全部提供且非空白/非 null，类型必须匹配
            List<String> missingPk = new ArrayList<>();
            Map<Integer, Object> pkValues = new LinkedHashMap<>();
            List<Object> pkParts = new ArrayList<>();
            String typeError = null;
            for (String pkName : schema.primaryKey()) {
                Column col = schema.columnByName(pkName);
                Object v = raw.get(pkName);
                if (v == null || (v instanceof String s && s.isEmpty())) {
                    missingPk.add(pkName);
                    continue;
                }
                try {
                    Object coerced = col.type().coerce(v);
                    pkValues.put(col.id(), coerced);
                    pkParts.add(coerced);
                } catch (IllegalArgumentException e) {
                    typeError = "primary key column '" + pkName + "': " + e.getMessage();
                    break;
                }
            }
            if (typeError != null) {
                rejected.add(new RejectedRecord(i, null, RejectReason.TYPE_MISMATCH,
                        typeError, raw));
                continue;
            }
            if (!missingPk.isEmpty()) {
                rejected.add(new RejectedRecord(i, pkDisplay(raw, schema),
                        RejectReason.MISSING_PRIMARY_KEY,
                        "missing or empty primary key columns: " + missingPk, raw));
                continue;
            }
            String pk = KeyCodec.encode(pkParts);
            // 批内重复主键
            if (!seenInBatch.add(pk)) {
                rejected.add(new RejectedRecord(i, pk,
                        RejectReason.DUPLICATE_PRIMARY_KEY_IN_BATCH,
                        "duplicate primary key within the same delete batch", raw));
                continue;
            }
            // 主键必须存在于当前快照
            if (!existing.contains(pk)) {
                rejected.add(new RejectedRecord(i, pk, RejectReason.PRIMARY_KEY_NOT_FOUND,
                        "primary key does not exist in the current snapshot", raw));
                continue;
            }
            tombstones.add(Row.tombstone(pkValues));
        }
        return new Validated(tombstones, rejected);
    }

    private static String pkDisplay(Map<String, Object> raw, Schema schema) {
        List<Object> parts = new ArrayList<>();
        for (String pkName : schema.primaryKey()) {
            parts.add(raw.get(pkName));
        }
        return KeyCodec.encode(parts);
    }
}
