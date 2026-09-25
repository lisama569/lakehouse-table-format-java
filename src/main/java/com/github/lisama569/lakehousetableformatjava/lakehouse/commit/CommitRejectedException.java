package com.github.lisama569.lakehousetableformatjava.lakehouse.commit;

import java.util.List;

/** 整次提交被拒绝时抛出，携带可区分原因；数据行错误时附带逐行明细。 */
public class CommitRejectedException extends RuntimeException {
    private final RejectReason reason;
    private final List<RowRejection> rowRejections;

    public CommitRejectedException(RejectReason reason, String message) {
        this(reason, message, List.of());
    }

    public CommitRejectedException(RejectReason reason, String message, List<RowRejection> rowRejections) {
        super(message);
        this.reason = reason;
        this.rowRejections = List.copyOf(rowRejections);
    }

    public RejectReason getReason() { return reason; }
    public List<RowRejection> getRowRejections() { return rowRejections; }
}
