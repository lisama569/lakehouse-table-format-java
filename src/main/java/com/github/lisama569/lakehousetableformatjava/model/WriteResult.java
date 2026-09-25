package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;

/** 写入结果：成功时给出新版本与快照标识，失败（整批拒绝）时列出全部问题记录。 */
public record WriteResult(boolean committed,
                          Long committedVersion,
                          String snapshotId,
                          long acceptedCount,
                          List<RejectedRecord> rejected) {
}
