package com.github.lisama569.lakehousetableformatjava.service;

import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
import com.github.lisama569.lakehousetableformatjava.storage.Catalog;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 对外门面：封装 catalog 打开表后的各类操作。 */
@Service
public class TableService {

    private final Catalog catalog;

    public TableService(Catalog catalog) {
        this.catalog = catalog;
    }

    public Table createTable(String name, List<ColumnSpec> columns, List<String> primaryKey) {
        return catalog.createTable(name, columns, primaryKey);
    }

    public Table open(String name) {
        return catalog.openTable(name);
    }

    public WriteResult write(String table, WriteRequest request) {
        return catalog.openTable(table).commit(request);
    }

    public Snapshot evolve(String table, SchemaChangeRequest request) {
        return catalog.openTable(table).evolve(request);
    }

    public Snapshot compact(String table) {
        return catalog.openTable(table).compact();
    }

    public WriteResult delete(String table,
                              com.github.lisama569.lakehousetableformatjava.model.DeleteRequest request) {
        return catalog.openTable(table).delete(request);
    }

    public com.github.lisama569.lakehousetableformatjava.model.VacuumResult vacuum(
            String table, com.github.lisama569.lakehousetableformatjava.model.RetentionPolicy policy) {
        return catalog.openTable(table).vacuum(policy);
    }

    /** version 为 null 时读头快照，否则读指定历史版本。 */
    public List<Map<String, Object>> read(String table, Long version) {
        Table t = catalog.openTable(table);
        return version == null ? t.readLatest() : t.readAt(version);
    }

    public List<Snapshot> history(String table) {
        return catalog.openTable(table).history();
    }
}
