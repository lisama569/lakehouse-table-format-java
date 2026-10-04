package com.github.lisama569.lakehousetableformatjava.model;

/** 记录被拒绝的可区分原因。 */
public enum RejectReason {
    /** 缺少主键列或主键值为 null/空白。 */
    MISSING_PRIMARY_KEY,
    /** 字段值与列声明类型不匹配。 */
    TYPE_MISMATCH,
    /** 出现表结构中不存在的列。 */
    UNKNOWN_COLUMN,
    /** 非主键列缺失且列不可空、无缺省值。 */
    MISSING_REQUIRED_COLUMN,
    /** APPEND 模式下主键在表中已存在。 */
    PRIMARY_KEY_ALREADY_EXISTS,
    /** 同一批次内重复主键。 */
    DUPLICATE_PRIMARY_KEY_IN_BATCH,
    /** DELETE 要删除的主键在当前快照中不存在。 */
    PRIMARY_KEY_NOT_FOUND
}
