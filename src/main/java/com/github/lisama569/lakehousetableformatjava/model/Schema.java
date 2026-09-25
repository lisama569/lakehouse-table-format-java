package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 表结构（schema 版本随提交版本单调递增）。
 *
 * <p>列按稳定的列 ID 寻址：列改名（RENAME）只改 name，ID 不变；
 * 删列（DROP）后 ID 不复用。数据文件中以列 ID 为键，因此改名/删列都不会
 * 让历史快照中的数据错列。每个快照都内嵌当时的完整 schema，使历史可独立复现。
 */
public record Schema(int version, List<Column> columns, List<String> primaryKey) {

    public Schema {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
    }

    private Map<String, Column> byName() {
        return columns.stream().collect(Collectors.toMap(Column::name, Function.identity()));
    }

    private Map<Integer, Column> byId() {
        return columns.stream().collect(Collectors.toMap(Column::id, Function.identity()));
    }

    /** 按列名查找，不存在返回 null。 */
    public Column columnByName(String name) {
        return byName().get(name);
    }

    /** 按列 ID 查找，不存在返回 null（例如旧文件中保存了已删除的列）。 */
    public Column columnById(int id) {
        return byId().get(id);
    }

    /** 主键列的 ID 列表，按主键声明顺序。 */
    public List<Integer> primaryKeyIds() {
        return primaryKey.stream().map(n -> byName().get(n).id()).toList();
    }
}
