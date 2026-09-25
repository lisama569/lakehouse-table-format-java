package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;

/**
 * 不可变快照：某一提交版本下表的全部状态。
 * parentVersion 形成提交链，实现乐观并发控制。
 */
public record Snapshot(long version,
                       String snapshotId,
                       long parentVersion,
                       String parentSnapshotId,
                       Schema schema,
                       List<DataFileRef> files,
                       SnapshotOperation operation,
                       long timestampMillis) {
}
