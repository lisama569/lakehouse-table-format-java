package com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot;

import java.util.List;

/** 表的一次不可变提交结果：固定的 schema、父快照与数据文件集合。 */
public final class Snapshot {
    private final long version;
    private final String snapshotId;
    private final Long parentSnapshot;
    private final long schemaId;
    private final long sequenceNumber;
    private final OperationType operation;
    private final List<DataFileMeta> dataFiles;
    private final long committedAtMillis;
    private final String summary;

    public Snapshot(long version, String snapshotId, Long parentSnapshot, long schemaId,
                    long sequenceNumber, OperationType operation, List<DataFileMeta> dataFiles,
                    long committedAtMillis, String summary) {
        this.version = version;
        this.snapshotId = snapshotId;
        this.parentSnapshot = parentSnapshot;
        this.schemaId = schemaId;
        this.sequenceNumber = sequenceNumber;
        this.operation = operation;
        this.dataFiles = List.copyOf(dataFiles);
        this.committedAtMillis = committedAtMillis;
        this.summary = summary;
    }

    public long getVersion() { return version; }
    public String getSnapshotId() { return snapshotId; }
    public Long getParentSnapshot() { return parentSnapshot; }
    public long getSchemaId() { return schemaId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public OperationType getOperation() { return operation; }
    public List<DataFileMeta> getSnapshotDataFiles() { return dataFiles; }
    public long getCommittedAtMillis() { return committedAtMillis; }
    public String getSummary() { return summary; }
}
