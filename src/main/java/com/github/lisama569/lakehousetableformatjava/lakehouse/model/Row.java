package com.github.lisama569.lakehousetableformatjava.lakehouse.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 行相关的确定性工具：主键规范化、按 schema 投影成列名->值。 */
public final class Row {
    /** 不会出现在转义后文本中的分隔符。 */
    private static final char DELIM = '\u0001';

    private Row() {}

    /**
     * 由主键列值（必须已按列类型规范化、非空）生成确定性的内部键。
     * 对反斜杠与分隔符做转义，保证不同列值组合不会碰撞；null 以固定占位符表示（主键正常不会为 null）。
     */
    public static String canonicalKey(List<Object> keyValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.size(); i++) {
            if (i > 0) {
                sb.append(DELIM);
            }
            Object v = keyValues.get(i);
            if (v == null) {
                sb.append("\\0");
            } else {
                String s = v.toString();
                for (int j = 0; j < s.length(); j++) {
                    char ch = s.charAt(j);
                    if (ch == '\\') {
                        sb.append("\\\\");
                    } else if (ch == DELIM) {
                        sb.append("\\u0001");
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 把按列 id 存储的值投影为某 schema 版本下的 列名->值（LinkedHashMap 保持列定义顺序）。
     * 数据文件中缺失的列（新增列）稳定地取该列缺省值；已删除的列因不在目标 schema 中而自然不可见，
     * 因此改名/删列都不会让历史快照错列。
     */
    public static Map<String, Object> project(Map<Integer, Object> valuesById, TableSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ColumnDef col : schema.getColumns()) {
            Object v = valuesById.containsKey(col.getId()) ? valuesById.get(col.getId()) : col.getDefaultValue();
            out.put(col.getName(), v);
        }
        return out;
    }
}
