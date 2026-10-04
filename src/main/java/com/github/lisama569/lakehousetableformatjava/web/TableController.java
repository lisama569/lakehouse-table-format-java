package com.github.lisama569.lakehousetableformatjava.web;

import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionPolicy;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.VacuumResult;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
import com.github.lisama569.lakehousetableformatjava.service.TableService;
import com.github.lisama569.lakehousetableformatjava.table.Table;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 湖仓表对外 REST 接口。 */
@RestController
@RequestMapping("/api/tables")
public class TableController {

    private final TableService tableService;

    public TableController(TableService tableService) {
        this.tableService = tableService;
    }

    public record CreateTableRequest(List<ColumnSpec> columns, List<String> primaryKey) {
    }

    @PostMapping("/{name}")
    public Snapshot create(@PathVariable String name, @RequestBody CreateTableRequest request) {
        Table table = tableService.createTable(name, request.columns(), request.primaryKey());
        return table.currentSnapshot();
    }

    @PostMapping("/{name}/write")
    public WriteResult write(@PathVariable String name, @RequestBody WriteRequest request) {
        return tableService.write(name, request);
    }

    @PostMapping("/{name}/schema")
    public Snapshot evolve(@PathVariable String name, @RequestBody SchemaChangeRequest request) {
        return tableService.evolve(name, request);
    }

    @PostMapping("/{name}/compact")
    public Snapshot compact(@PathVariable String name) {
        return tableService.compact(name);
    }

    @PostMapping("/{name}/delete")
    public WriteResult delete(@PathVariable String name, @RequestBody DeleteRequest request) {
        return tableService.delete(name, request);
    }

    @PostMapping("/{name}/vacuum")
    public VacuumResult vacuum(@PathVariable String name, @RequestBody RetentionPolicy policy) {
        return tableService.vacuum(name, policy);
    }

    @GetMapping("/{name}/rows")
    public List<Map<String, Object>> rows(@PathVariable String name,
                                          @RequestParam(required = false) Long version) {
        return tableService.read(name, version);
    }

    @GetMapping("/{name}/history")
    public List<Snapshot> history(@PathVariable String name) {
        return tableService.history(name);
    }
}
