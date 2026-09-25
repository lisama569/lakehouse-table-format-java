package com.github.lisama569.lakehousetableformatjava.model;

/** 建表时的列规格（列 ID 由 catalog 分配）。 */
public record ColumnSpec(String name, DataType type, boolean nullable, String defaultValue) {
}
