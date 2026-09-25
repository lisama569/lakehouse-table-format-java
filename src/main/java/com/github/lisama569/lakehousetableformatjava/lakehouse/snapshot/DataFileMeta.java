package com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot;

import java.util.Set;

/** 快照中引用的不可变数据文件元数据；文件一旦写入永不修改。 */
public final class DataFileMeta {
    private final String path;
    private final long recordCount;
    private final Set<String> primaryKeys;
    private final long addedSequenceNumber;

    public DataFileMeta(String path, long recordCount, Set<String> primaryKeys, long addedSequenceNumber) {
        this.path = path;
        this.recordCount = recordCount;
        this.primaryKeys = Set.copyOf(primaryKeys);
        this.addedSequenceNumber = addedSequenceNumber;
    }

    public String getPath() { return path; }
    public long getRecordCount() { return recordCount; }
    public Set<String> getPrimaryKeys() { return primaryKeys; }
    public long getAddedSequenceNumber() { return addedSequenceNumber; }
}
