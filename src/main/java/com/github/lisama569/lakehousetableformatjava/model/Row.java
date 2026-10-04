package com.github.lisama569.lakehousetableformatjava.model;

import java.util.Map;

/**
 * 一行数据：列 ID -> 值。数据文件与查询行均以列 ID 为键，
 * 因此列改名后历史文件仍能按当前 schema 正确解释。
 *
 * <p>{@code deleted=true} 表示这是一行“删除墓碑”（tombstone），只携带主键列的值。
 * 墓碑同样是不可变数据，按与普通行完全相同的来源坐标规则参与合并；它只在删除
 * 提交之后的快照中生效，历史快照仍按删除前的文件状态读出原行。
 * 旧数据文件没有该字段，Jackson 反序列化时取 Java 默认值 {@code false}，向后兼容。
 */
public record Row(Map<Integer, Object> values, boolean deleted) {

    public Row(Map<Integer, Object> values) {
        this(values, false);
    }
}
