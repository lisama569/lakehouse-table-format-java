package com.github.lisama569.lakehousetableformatjava.model;

import java.util.Objects;

/**
 * 列定义。列以稳定的列 ID 标识，改名不会影响历史数据定位。
 */
public record Column(int id, String name, DataType type, boolean nullable, String defaultValue) {
    public Column {
        Objects.requireNonNull(name, "column name");
        Objects.requireNonNull(type, "column type");
    }
}
