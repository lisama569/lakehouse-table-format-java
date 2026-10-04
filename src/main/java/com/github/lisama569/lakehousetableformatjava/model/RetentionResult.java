package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;

/**
 * 一次保留回收的结果：回收时的头版本、被清除的快照版本、被删除与
 * 被保留的数据文件，便于审计与日志判定。
 */
public record RetentionResult(long headVersion,
                              String headSnapshotId,
                              List<Long> expiredSnapshotVersions,
                              List<Long> retainedSnapshotVersions,
                              List<String> deletedDataFiles,
                              List<String> retainedDataFiles) {
    public RetentionResult {
        expiredSnapshotVersions = List.copyOf(expiredSnapshotVersions);
        retainedSnapshotVersions = List.copyOf(retainedSnapshotVersions);
        deletedDataFiles = List.copyOf(deletedDataFiles);
        retainedDataFiles = List.copyOf(retainedDataFiles);
    }
}
