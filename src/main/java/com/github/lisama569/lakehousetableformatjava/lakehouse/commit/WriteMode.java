package com.github.lisama569.lakehousetableformatjava.lakehouse.commit;

/** 写入语义：APPEND 仅允许新主键，MERGE 允许覆盖更新。 */
public enum WriteMode {
    APPEND,
    MERGE
}
