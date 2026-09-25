package com.github.lisama569.lakehousetableformatjava.lakehouse.storage;

import com.github.lisama569.lakehousetableformatjava.lakehouse.model.ColumnDef;
import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.DataFileMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * metadata/table-state.json 的磁盘结构：表的根指针与历史清单。
 * 该文件通过"写临时文件 + 原子 move"替换，保证已发布快照要么完整可见、要么不可见。
 */
public final class TableStateFile {

    public static final class SnapshotEntry {
        public long version;
        public String snapshotId;
        public Long parentVersion;
        public long schemaId;
        public long sequenceNumber;
        public String operation;
        public List<DataFileMeta> dataFiles = new ArrayList<>();
        public long committedAt;
        public String summary = "";

        public SnapshotEntry() {
        }
    }

    private String tableName;
    private int nextColumnId;
    private List<ColumnDef> keyColumns = new ArrayList<>();
    private long currentVersion;
    private List<SnapshotEntry> snapshots = new ArrayList<>();

    public TableStateFile() {
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public int getNextColumnId() { return nextColumnId; }
    public void setNextColumnId(int nextColumnId) { this.nextColumnId = nextColumnId; }

    public List<ColumnDef> getKeyColumns() { return keyColumns; }
    public void setKeyColumns(List<ColumnDef> keyColumns) { this.keyColumns = keyColumns; }

    public long getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(long currentVersion) { this.currentVersion = currentVersion; }

    public List<SnapshotEntry> getSnapshots() { return snapshots; }
    public void setSnapshots(List<SnapshotEntry> snapshots) { this.snapshots = snapshots; }
}
