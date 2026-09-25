package com.github.lisama569.lakehousetableformatjava.lakehouse.storage;

import com.github.lisama569.lakehousetableformatjava.lakehouse.model.ColumnDef;

import java.util.ArrayList;
import java.util.List;

/** metadata/schema-N.json：一个不可变的 schema 版本。 */
public final class SchemaFile {
    private long schemaId;
    private int nextColumnId;
    private List<ColumnDef> columns = new ArrayList<>();
    private List<Integer> keyColumnIds = new ArrayList<>();

    public SchemaFile() {
    }

    public long getSchemaId() { return schemaId; }
    public void setSchemaId(long schemaId) { this.schemaId = schemaId; }

    public int getNextColumnId() { return nextColumnId; }
    public void setNextColumnId(int nextColumnId) { this.nextColumnId = nextColumnId; }

    public List<ColumnDef> getColumns() { return columns; }
    public void setColumns(List<ColumnDef> columns) { this.columns = columns; }

    public List<Integer> getKeyColumnIds() { return keyColumnIds; }
    public void setKeyColumnIds(List<Integer> keyColumnIds) { this.keyColumnIds = keyColumnIds; }
}
