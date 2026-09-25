package com.github.lisama569.lakehousetableformatjava.lakehouse.api.web;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitReport;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RowRejection;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.evolve.SchemaChange;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;

import java.util.List;
import java.util.Map;

/** REST 接口使用的数据传输对象集合。 */
public final class ApiDtos {
    private ApiDtos() {}

    public record ColumnSpecDto(String name, DataType type, Boolean nullable, Object defaultValue) {}

    public record CreateTableRequest(String tableName, List<ColumnSpecDto> columns, List<String> primaryKey) {}

    public record WriteRequest(Long baseVersion, WriteMode mode, List<Map<String, Object>> rows) {}

    public record EvolveRequest(Long baseVersion, List<SchemaChange> changes) {}

    public record CompactRequest(Long baseVersion) {}

    public record ReadResponse(long requestedVersion, long currentVersion, String snapshotId,
                               List<Map<String, Object>> rows) {}

    public record SnapshotView(long version, String snapshotId, Long parentVersion, long schemaId,
                               long sequenceNumber, String operation, long dataFileCount,
                               long committedAt, String summary) {}

    public record TableInfoResponse(String tableName, SnapshotView current, List<SnapshotView> snapshots) {}

    public record CommitResponse(CommitReport commit, TableInfoResponse table) {}

    public record ErrorResponse(String error, String reason, String message, List<RowRejection> rowRejections) {}
}
