package com.github.lisama569.lakehousetableformatjava.storage;

import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.table.Table;

import java.util.List;

/** 表目录：建表（含初始快照）、打开、删除、列出表。 */
public interface Catalog {

    Table createTable(String name, List<ColumnSpec> columns, List<String> primaryKey);

    Table openTable(String name);

    void dropTable(String name);

    List<String> listTables();
}
