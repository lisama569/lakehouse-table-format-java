package com.github.lisama569.lakehousetableformatjava.lakehouse.storage;

import java.util.List;

/** 数据文件的完整磁盘内容（不可变；文件名即内容标识的一部分）。 */
public final class StoredDataFile {
    private String path;
    private long sequenceNumber;
    private List<DataFileRecord> records;

    public StoredDataFile() {
    }

    public StoredDataFile(String path, long sequenceNumber, List<DataFileRecord> records) {
        this.path = path;
        this.sequenceNumber = sequenceNumber;
        this.records = records;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public List<DataFileRecord> getRecords() { return records; }
    public void setRecords(List<DataFileRecord> records) { this.records = records; }
}
