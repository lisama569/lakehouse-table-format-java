package com.github.lisama569.lakehousetableformatjava.lakehouse.api.web;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.Snapshot;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 本地湖仓表的 REST 对外接口。 */
@RestController
@RequestMapping("/api/tables")
public class LakehouseController {

    private final LakehouseCatalog catalog;

    public LakehouseController(LakehouseCatalog catalog) {
        this.catalog = catalog;
    }

    @PutMapping("/{tableName}")
    public synchronized ApiDtos.TableInfoResponse createTable(@PathVariable String tableName,
                                                              @RequestBody ApiDtos.CreateTableRequest request) {
        if (request == null || request.columns() == null) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST, "请求体缺少 columns");
        }
        String name = request.tableName() != null ? request.tableName() : tableName;
        List<LakehouseCatalog.ColumnSpec> specs = request.columns().stream()
                .map(c -> new LakehouseCatalog.ColumnSpec(c.name(), c.type(), c.nullable(), c.defaultValue()))
                .toList();
        Table table = catalog.createTable(name, specs,
                request.primaryKey() == null ? List.of() : request.primaryKey());
        return describe(name, table);
    }

    @GetMapping("/{tableName}")
    public ApiDtos.TableInfoResponse describe(@PathVariable String tableName) {
        return describe(tableName, catalog.loadTable(tableName));
    }

    @PostMapping("/{tableName}/write")
    public ApiDtos.CommitResponse write(@PathVariable String tableName,
                                        @RequestBody ApiDtos.WriteRequest request) {
        Table table = catalog.loadTable(tableName);
        if (request == null || request.baseVersion() == null || request.mode() == null) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                    "write 请求必须提供 baseVersion 与 mode");
        }
        return new ApiDtos.CommitResponse(
                table.write(request.baseVersion(), request.mode(),
                        request.rows() == null ? List.of() : request.rows()),
                describe(tableName, table));
    }

    @PostMapping("/{tableName}/evolve")
    public ApiDtos.CommitResponse evolve(@PathVariable String tableName,
                                         @RequestBody ApiDtos.EvolveRequest request) {
        Table table = catalog.loadTable(tableName);
        if (request == null || request.baseVersion() == null) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                    "evolve 请求必须提供 baseVersion");
        }
        return new ApiDtos.CommitResponse(
                table.evolve(request.baseVersion(), request.changes() == null ? List.of() : request.changes()),
                describe(tableName, table));
    }

    @PostMapping("/{tableName}/compact")
    public ApiDtos.CommitResponse compact(@PathVariable String tableName,
                                          @RequestBody(required = false) ApiDtos.CompactRequest request) {
        Table table = catalog.loadTable(tableName);
        long base = request == null || request.baseVersion() == null
                ? table.currentSnapshot().getVersion() : request.baseVersion();
        return new ApiDtos.CommitResponse(table.compact(base), describe(tableName, table));
    }

    @DeleteMapping("/{tableName}")
    public Map<String, Object> drop(@PathVariable String tableName) {
        catalog.dropTable(tableName);
        return Map.of("dropped", tableName);
    }

    @GetMapping("/{tableName}/snapshots/{version}/rows")
    public ApiDtos.ReadResponse readAt(@PathVariable String tableName, @PathVariable long version) {
        Table table = catalog.loadTable(tableName);
        Map<String, Map<String, Object>> rows = table.readAt(version);
        return toReadResponse(table, version, rows);
    }

    @GetMapping("/{tableName}/rows")
    public ApiDtos.ReadResponse readLatest(@PathVariable String tableName) {
        Table table = catalog.loadTable(tableName);
        long version = table.currentSnapshot().getVersion();
        return toReadResponse(table, version, table.readLatest());
    }

    private ApiDtos.ReadResponse toReadResponse(Table table, long requestedVersion,
                                                Map<String, Map<String, Object>> rows) {
        Snapshot snap = table.snapshotAt(requestedVersion);
        List<Map<String, Object>> list = new ArrayList<>();
        rows.forEach((pk, columnValues) -> {
            Map<String, Object> wrapped = new java.util.LinkedHashMap<>();
            wrapped.put("_key", pk);
            wrapped.put("values", columnValues);
            list.add(wrapped);
        });
        return new ApiDtos.ReadResponse(requestedVersion, table.currentSnapshot().getVersion(),
                snap.getSnapshotId(), list);
    }

    private ApiDtos.TableInfoResponse describe(String tableName, Table table) {
        List<ApiDtos.SnapshotView> views = new ArrayList<>();
        for (Snapshot s : table.listSnapshots()) {
            views.add(new ApiDtos.SnapshotView(s.getVersion(), s.getSnapshotId(),
                    s.getParentSnapshot() == null ? null : s.getParentSnapshot(),
                    s.getSchemaId(), s.getSequenceNumber(), s.getOperation().name(),
                    s.getSnapshotDataFiles().size(), s.getCommittedAtMillis(), s.getSummary()));
        }
        Snapshot cur = table.currentSnapshot();
        return new ApiDtos.TableInfoResponse(tableName,
                new ApiDtos.SnapshotView(cur.getVersion(), cur.getSnapshotId(),
                        cur.getParentSnapshot() == null ? null : cur.getParentSnapshot(),
                        cur.getSchemaId(), cur.getSequenceNumber(), cur.getOperation().name(),
                        cur.getSnapshotDataFiles().size(), cur.getCommittedAtMillis(), cur.getSummary()),
                views);
    }
}
