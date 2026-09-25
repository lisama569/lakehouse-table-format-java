package com.github.lisama569.lakehousetableformatjava.lakehouse.commit;

import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.OperationType;

/** 成功提交的结论报告。 */
public record CommitReport(
        String tableName,
        long version,
        String snapshotId,
        long parentVersion,
        long schemaId,
        OperationType operation,
        long sequenceNumber,
        long rowsAccepted,
        long duplicatesInBatch,
        String basis
) {
}
