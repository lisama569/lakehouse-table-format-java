package com.github.lisama569.lakehousetableformatjava.lakehouse.table;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RowRejection;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.ColumnDef;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.Row;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.TableSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 针对某个 schema 版本对入站行做确定性校验与规范化。
 * 检查按固定顺序进行，因此每条错误的原因类别可区分、可复现：
 * 未知列 → 主键（缺失/空 vs 类型不匹配，二者互斥）→ 非主键列（缺必填列 vs 类型不匹配）。
 */
final class RowValidator {

    static final class NormalizedRow {
        final String key;
        final Map<Integer, Object> valuesById;

        NormalizedRow(String key, Map<Integer, Object> valuesById) {
            this.key = key;
            this.valuesById = valuesById;
        }
    }

    private final TableSchema schema;
    private final Set<Integer> keyIdSet;

    RowValidator(TableSchema schema) {
        this.schema = schema;
        this.keyIdSet = new HashSet<>(schema.getKeyColumnIds());
    }

    /** 校验全部行；只要有一条非法就返回明细列表（调用方据此整批拒绝，绝不部分写入）。 */
    List<RowRejection> validate(List<Map<String, Object>> rows) {
        List<RowRejection> problems = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (row == null) {
                problems.add(new RowRejection(i, null, RejectReason.INVALID_REQUEST, "记录为 null"));
                continue;
            }
            // 1) 未知列
            for (String field : row.keySet()) {
                if (schema.findByName(field) == null) {
                    problems.add(new RowRejection(i, null, RejectReason.UNKNOWN_COLUMN,
                            "未知字段（schema v%d 中不存在该列）: %s".formatted(schema.getSchemaId(), field)));
                }
            }
            // 2) 主键：缺失/为空 -> MISSING_PRIMARY_KEY；类型错 -> TYPE_MISMATCH（互斥，只报一个主键类原因）
            List<Object> keyValues = new ArrayList<>();
            boolean keyUsable = true;
            for (int keyId : schema.getKeyColumnIds()) {
                ColumnDef keyCol = schema.findById(keyId);
                Object raw = row.get(keyCol.getName());
                if (raw == null) {
                    problems.add(new RowRejection(i, null, RejectReason.MISSING_PRIMARY_KEY,
                            "缺少主键列或主键为空: %s（主键列: %s）"
                                    .formatted(keyCol.getName(), keyColumnNames())));
                    keyUsable = false;
                    break;
                }
                try {
                    keyValues.add(keyCol.getType().normalize(raw));
                } catch (IllegalArgumentException ex) {
                    problems.add(new RowRejection(i, null, RejectReason.TYPE_MISMATCH,
                            "主键列 %s 类型不匹配: %s".formatted(keyCol.getName(), ex.getMessage())));
                    keyUsable = false;
                    break;
                }
            }
            String key = keyUsable ? Row.canonicalKey(keyValues) : null;
            // 3) 非主键列
            for (ColumnDef col : schema.getColumns()) {
                if (keyIdSet.contains(col.getId())) {
                    continue; // 主键列已在上一步处理
                }
                if (!row.containsKey(col.getName())) {
                    if (!col.isNullable() && col.getDefaultValue() == null) {
                        problems.add(new RowRejection(i, key, RejectReason.MISSING_REQUIRED_COLUMN,
                                "缺少非空列且无缺省值: " + col.getName()));
                    }
                    continue;
                }
                Object raw = row.get(col.getName());
                if (raw == null) {
                    if (!col.isNullable()) {
                        problems.add(new RowRejection(i, key, RejectReason.MISSING_REQUIRED_COLUMN,
                                "非空列收到 null: " + col.getName()));
                    }
                    continue;
                }
                try {
                    col.getType().normalize(raw);
                } catch (IllegalArgumentException ex) {
                    problems.add(new RowRejection(i, key, RejectReason.TYPE_MISMATCH,
                            "列 %s 类型不匹配: %s".formatted(col.getName(), ex.getMessage())));
                }
            }
        }
        return problems;
    }

    /** 将已通过校验的行规范化为 列id->值（仅含有显式值的列，其余读时取缺省值）。 */
    NormalizedRow normalize(Map<String, Object> row) {
        List<Object> keyValues = new ArrayList<>();
        for (int keyId : schema.getKeyColumnIds()) {
            ColumnDef keyCol = schema.findById(keyId);
            keyValues.add(keyCol.getType().normalize(row.get(keyCol.getName())));
        }
        String key = Row.canonicalKey(keyValues);
        Map<Integer, Object> values = new LinkedHashMap<>();
        for (ColumnDef col : schema.getColumns()) {
            if (row.containsKey(col.getName())) {
                DataType type = col.getType();
                Object raw = row.get(col.getName());
                values.put(col.getId(), raw == null ? null : type.normalize(raw));
            }
        }
        return new NormalizedRow(key, values);
    }

    private String keyColumnNames() {
        List<String> names = new ArrayList<>();
        for (int id : schema.getKeyColumnIds()) {
            names.add(schema.findById(id).getName());
        }
        return String.join(", ", names);
    }
}
