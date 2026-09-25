package com.github.lisama569.lakehousetableformatjava.lakehouse.table;

import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;

import java.util.List;
import java.util.Map;

/** 本地湖仓目录：建表与加载表。 */
public interface LakehouseCatalog {

    /** 创建表并返回 v0 初始化快照。columnDefs 每项为 name/type/nullable/defaultValue。 */
    Table createTable(String tableName,
                      List<ColumnSpec> columns,
                      List<String> primaryKeyColumns);

    /** 加载已存在的表，不存在抛 TABLE_NOT_FOUND。 */
    Table loadTable(String tableName);

    boolean tableExists(String tableName);

    void dropTable(String tableName);

    /** 列定义入参；nullable 为 null 时按可空处理。 */
    record ColumnSpec(String name, DataType type, Boolean nullable, Object defaultValue) {}
}
