package com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot;

/** 快照的产生来源。 */
public enum OperationType {
    INITIALIZE,
    APPEND,
    MERGE,
    SCHEMA_CHANGE,
    COMPACT
}
