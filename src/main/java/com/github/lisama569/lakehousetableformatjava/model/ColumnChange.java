package com.github.lisama569.lakehousetableformatjava.model;

/**
 * 单次结构演进操作。
 * kind=ADD   : 按 spec 新增列（old rows 用 defaultValue / null 补值，语义确定稳定）
 * kind=RENAME: 按旧列名改名（列 ID 不变，历史文件不错列）
 * kind=DROP  : 删除列（历史快照保留旧 schema，旧列数据仍在文件中，不影响历史复现）
 */
public record ColumnChange(Kind kind, String columnName, String newName, ColumnSpec spec) {
    public enum Kind { ADD, RENAME, DROP }
}
