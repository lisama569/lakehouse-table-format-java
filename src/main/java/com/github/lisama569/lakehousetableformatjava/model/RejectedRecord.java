package com.github.lisama569.lakehousetableformatjava.model;

import java.util.Map;

/** 被拒绝的记录：批次内序号、主键、原因与可读说明。 */
public record RejectedRecord(int batchIndex,
                             String primaryKeyValue,
                             RejectReason reason,
                             String message,
                             Map<String, Object> record) {
}
