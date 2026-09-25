package com.github.lisama569.lakehousetableformatjava.model;

/** 写入模式：APPEND 不允许覆盖已有主键；MERGE 为按主键 upsert。 */
public enum WriteMode {
    APPEND,
    MERGE
}
