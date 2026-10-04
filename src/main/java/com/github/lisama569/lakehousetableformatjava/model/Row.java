package com.github.lisama569.lakehousetableformatjava.model;

import java.util.Map;

/**
 * 一行数据：列 ID -> 值。数据文件与查询行均以列 ID 为键，
 * 因此列改名后历史文件仍能按当前 schema 正确解释。
 *
 * <p>{@code deleted=true} 表示删除墓碑：只携带主键列的值，参与与数据行完全
 * 相同的 (commitVersion, seq) 胜负规则；墓碑胜出的主键在当前快照中不可见，
 * 但历史快照仍引用旧文件，删除前的版本照常可读。旧格式数据文件没有
 * {@code deleted} 字段，反序列化时按 false 处理（向后兼容）。
 */
public record Row(Map<Integer, Object> values, boolean deleted) {

    /** 普通数据行。 */
    public Row(Map<Integer, Object> values) {
        this(values, false);
    }

    /** 构造删除墓碑：values 只需包含主键列。 */
    public static Row tombstone(Map<Integer, Object> pkValues) {
        return new Row(pkValues, true);
    }
}
