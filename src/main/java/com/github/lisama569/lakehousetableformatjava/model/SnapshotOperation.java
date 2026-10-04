package com.github.lisama569.lakehousetableformatjava.model;

/** 快照产生的操作类型，便于日志与审计解释判定依据。 */
public enum SnapshotOperation {
    APPEND,
    MERGE,
    DELETE,
    SCHEMA_CHANGE,
    COMPACTION,
    INIT
}
