package com.github.lisama569.lakehousetableformatjava.lakehouse.commit;

/** 单条记录被拒绝的明细。 */
public record RowRejection(int rowIndex, String primaryKey, RejectReason reason, String detail) {
}
