package com.github.lisama569.lakehousetableformatjava.table;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.exception.SchemaEvolutionException;
import com.github.lisama569.lakehousetableformatjava.model.Column;
import com.github.lisama569.lakehousetableformatjava.model.DataFileRef;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionResult;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Schema;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.SnapshotOperation;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
import com.github.lisama569.lakehousetableformatjava.storage.TableStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link Table} 的本地实现。
 *
 * <p>并发模型：表级锁把同 JVM 的提交串行化，提交时进行 OCC 校验
 * （expectedVersion 必须等于当前头版本），随后先写不可变数据文件/快照，
 * 再以 CAS 推进 HEAD。基于过期快照的写入被 {@link ConflictException} 明确拒绝，
 * 绝不覆盖已提交结果。
 *
 * <p>合并语义：数据文件不可变，读取时按主键合并，胜负由
 * “最大提交版本，再按文件在快照中的出现顺序、行序”确定（后写胜），
 * 与 compaction 重写文件时使用的规则完全相同，因此整理前后结果一致。
 */
public final class LocalTable implements Table {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTable.class);

    private final TableStorage storage;
    private final Object commitLock;
    private final String tableName;

    public LocalTable(String tableName, TableStorage storage, Object commitLock) {
        this.tableName = tableName;
        this.storage = storage;
        this.commitLock = commitLock;
    }

    @Override
    public String name() {
        return tableName;
    }

    @Override
    public Snapshot currentSnapshot() {
        return storage.readSnapshot(storage.readHeadVersion());
    }

    @Override
    public Snapshot snapshotAt(long version) {
        return storage.readSnapshot(version);
    }

    @Override
    public List<Snapshot> history() {
        return storage.readAllSnapshots();
    }

    @Override
    public WriteResult commit(WriteRequest request) {
        if (request == null || request.records() == null) {
            throw new IllegalArgumentException("write request and records must not be null");
        }
        WriteMode mode = request.mode() == null ? WriteMode.APPEND : request.mode();
        synchronized (commitLock) {
            long baseVersion = storage.readHeadVersion();
            if (request.expectedVersion() != null && request.expectedVersion() != baseVersion) {
                LOG.warn("[commit:reject] table={} reason=STALE_SNAPSHOT expectedVersion={} currentVersion={}",
                        tableName, request.expectedVersion(), baseVersion);
                throw new ConflictException(tableName, request.expectedVersion(), baseVersion);
            }

            Snapshot base = storage.readSnapshot(baseVersion);
            TableData data = TableData.load(storage, base);
            RecordValidator.Validated validated = RecordValidator.validate(
                    base.schema(), request.records(), data.keys(), mode);

            // 整批拒绝：有任何非法记录就不产生任何副作用，原因逐条返回
            if (!validated.ok()) {
                LOG.info("[commit:reject] table={} baseVersion={} rejectedCount={} reasons={}",
                        tableName, baseVersion, validated.rejected().size(),
                        validated.rejected().stream().map(r -> r.reason().name()).toList());
                return new WriteResult(false, null, null, 0L, validated.rejected());
            }

            long newVersion = baseVersion + 1;
            List<DataFileRef> newFiles;
            if (mode == WriteMode.APPEND) {
                // 追加：写一个新的不可变文件，保留旧文件列表
                DataFileRef file = storage.writeDataFile(
                        newVersion + "-0.json", validated.rows(), newVersion);
                newFiles = new ArrayList<>(base.files());
                newFiles.add(file);
            } else {
                // 合并：在合并后的全量行上覆盖本批，重写为一个新文件（旧文件保留给历史快照）
                data.overwriteWithNewRows(validated.rows(), newVersion);
                DataFileRef file = storage.writeDataFile(
                        newVersion + "-0.json", data.liveRows(), newVersion);
                newFiles = new ArrayList<>(List.of(file));
            }

            String snapshotId = newSnapshotId();
            Snapshot next = new Snapshot(newVersion, snapshotId, baseVersion, base.snapshotId(),
                    base.schema(), List.copyOf(newFiles),
                    mode == WriteMode.APPEND ? SnapshotOperation.APPEND : SnapshotOperation.MERGE,
                    System.currentTimeMillis());
            storage.writeSnapshot(next);
            storage.casHead(baseVersion, newVersion);
            LOG.info("[commit:accept] table={} mode={} baseVersion={} committedVersion={} snapshotId={} records={} basis=headCAS(base={})",
                    tableName, mode, baseVersion, newVersion, snapshotId, validated.rows().size(), baseVersion);
            return new WriteResult(true, newVersion, snapshotId, validated.rows().size(), List.of());
        }
    }

    private static String newSnapshotId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public WriteResult delete(DeleteRequest request) {
        if (request == null || request.keys() == null || request.keys().isEmpty()) {
            throw new IllegalArgumentException("delete request and keys must not be null/empty");
        }
        synchronized (commitLock) {
            long baseVersion = storage.readHeadVersion();
            if (request.expectedVersion() != null && request.expectedVersion() != baseVersion) {
                LOG.warn("[delete:reject] table={} reason=STALE_SNAPSHOT expectedVersion={} currentVersion={}",
                        tableName, request.expectedVersion(), baseVersion);
                throw new ConflictException(tableName, request.expectedVersion(), baseVersion);
            }

            Snapshot base = storage.readSnapshot(baseVersion);
            TableData data = TableData.load(storage, base);
            DeleteValidator.Validated validated = DeleteValidator.validate(
                    base.schema(), request.keys(), data.keys());

            // 整批拒绝：有任何非法记录就不产生任何副作用，原因逐条返回
            if (!validated.ok()) {
                LOG.info("[delete:reject] table={} baseVersion={} rejectedCount={} reasons={}",
                        tableName, baseVersion, validated.rejected().size(),
                        validated.rejected().stream().map(r -> r.reason().name()).toList());
                return new WriteResult(false, null, null, 0L, validated.rejected());
            }

            // 删除以墓碑文件提交：不修改任何旧文件，历史快照照常复现；
            // 墓碑的 commitVersion 高于被删行，按统一坐标规则胜出。
            long newVersion = baseVersion + 1;
            DataFileRef file = storage.writeDataFile(
                    newVersion + "-delete.json", validated.tombstones(), newVersion);
            List<DataFileRef> newFiles = new ArrayList<>(base.files());
            newFiles.add(file);

            String snapshotId = newSnapshotId();
            Snapshot next = new Snapshot(newVersion, snapshotId, baseVersion, base.snapshotId(),
                    base.schema(), List.copyOf(newFiles), SnapshotOperation.DELETE,
                    System.currentTimeMillis());
            storage.writeSnapshot(next);
            storage.casHead(baseVersion, newVersion);
            LOG.info("[delete:accept] table={} baseVersion={} committedVersion={} snapshotId={} deletedKeys={} basis=headCAS(base={})",
                    tableName, baseVersion, newVersion, snapshotId, validated.tombstones().size(),
                    baseVersion);
            return new WriteResult(true, newVersion, snapshotId, validated.tombstones().size(),
                    List.of());
        }
    }

    @Override
    public Snapshot evolve(SchemaChangeRequest request) {
        if (request == null || request.changes() == null || request.changes().isEmpty()) {
            throw new SchemaEvolutionException("schema change must contain at least one change");
        }
        synchronized (commitLock) {
            long baseVersion = storage.readHeadVersion();
            if (request.expectedVersion() != null && request.expectedVersion() != baseVersion) {
                LOG.warn("[evolve:reject] table={} reason=STALE_SNAPSHOT expectedVersion={} currentVersion={}",
                        tableName, request.expectedVersion(), baseVersion);
                throw new ConflictException(tableName, request.expectedVersion(), baseVersion);
            }
            Snapshot base = storage.readSnapshot(baseVersion);

            // 以“待应用的列集合”逐步应用变更，使同一请求内 ADD+RENAME 可顺序生效
            List<Column> columns = new ArrayList<>(base.schema().columns());
            int nextId = columns.stream().mapToInt(Column::id).max().orElse(0) + 1;
            // 主键列名随本批 RENAME 同步更新（按列 ID 锚定身份）；
            // 主键列的 DROP 以 base schema 的列 ID 判定，不受同批改名影响。
            Set<Integer> pkIds = Set.copyOf(base.schema().primaryKeyIds());
            List<String> pkNames = new ArrayList<>(base.schema().primaryKey());

            for (com.github.lisama569.lakehousetableformatjava.model.ColumnChange change : request.changes()) {
                if (change.kind() == null || change.columnName() == null) {
                    throw new SchemaEvolutionException("invalid schema change: " + change);
                }
                int idx = indexOfColumn(columns, change.columnName());
                switch (change.kind()) {
                    case ADD -> {
                        if (idx >= 0) {
                            throw new SchemaEvolutionException(
                                    "cannot add column, name already exists: " + change.columnName());
                        }
                        var spec = change.spec();
                        if (spec == null || ColumnNames.isBlank(spec.name())) {
                            throw new SchemaEvolutionException("ADD requires a valid column spec");
                        }
                        if (!ColumnNames.isValid(spec.name())) {
                            throw new SchemaEvolutionException(
                                    "illegal new column name '" + spec.name()
                                            + "': must not have leading/trailing whitespace or control characters");
                        }
                        if (indexOfColumn(columns, spec.name()) >= 0) {
                            throw new SchemaEvolutionException(
                                    "cannot add column, name already exists: " + spec.name());
                        }
                        var type = spec.type() == null
                                ? com.github.lisama569.lakehousetableformatjava.model.DataType.STRING
                                : spec.type();
                        // 提前校验缺省值与类型匹配，避免脏 schema
                        if (spec.defaultValue() != null) {
                            try {
                                type.parseDefault(spec.defaultValue());
                            } catch (IllegalArgumentException e) {
                                throw new SchemaEvolutionException(
                                        "default value of new column '" + spec.name() + "' is invalid: "
                                                + e.getMessage());
                            }
                        }
                        if (!spec.nullable() && spec.defaultValue() == null) {
                            throw new SchemaEvolutionException(
                                    "new non-nullable column '" + spec.name()
                                            + "' must provide a default value (existing rows need it)");
                        }
                        columns.add(new Column(nextId++, spec.name(), type, spec.nullable(),
                                spec.defaultValue()));
                    }
                    case RENAME -> {
                        // 失败原因必须可区分：列不存在 / 新名为空白 / 新名非法 / 新名已存在
                        if (idx < 0) {
                            throw new SchemaEvolutionException(
                                    "cannot rename, column not found: " + change.columnName());
                        }
                        if (ColumnNames.isBlank(change.newName())) {
                            throw new SchemaEvolutionException(
                                    "cannot rename '" + change.columnName()
                                            + "': new name must not be null or blank");
                        }
                        if (!ColumnNames.isValid(change.newName())) {
                            throw new SchemaEvolutionException(
                                    "cannot rename '" + change.columnName() + "' to '" + change.newName()
                                            + "': new name must not have leading/trailing whitespace"
                                            + " or control characters");
                        }
                        if (indexOfColumn(columns, change.newName()) >= 0) {
                            throw new SchemaEvolutionException(
                                    "cannot rename, target name already exists: " + change.newName());
                        }
                        Column oldCol = columns.get(idx);
                        // 关键：列 ID 保持不变，只改名字，历史文件按 ID 不会错列
                        columns.set(idx, new Column(oldCol.id(), change.newName(), oldCol.type(),
                                oldCol.nullable(), oldCol.defaultValue()));
                        // 主键列同样允许改名：按列 ID 把主键声明里的旧名换成新名，
                        // 主键身份（列 ID 集合）不变，数据文件与墓碑无需改动。
                        for (int p = 0; p < pkNames.size(); p++) {
                            if (pkNames.get(p).equals(change.columnName())) {
                                pkNames.set(p, change.newName());
                            }
                        }
                    }
                    case DROP -> {
                        if (idx < 0) {
                            throw new SchemaEvolutionException(
                                    "cannot drop, column not found: " + change.columnName());
                        }
                        // 以列 ID 判定主键：主键列在同批先改名也依然禁止删除
                        if (pkIds.contains(columns.get(idx).id())) {
                            throw new SchemaEvolutionException(
                                    "cannot drop a primary key column: " + change.columnName());
                        }
                        columns.remove(idx);
                        // ID 不复用：nextId 已在分配时单调推进
                    }
                }
            }

            // 演进后主键列必须仍然存在（按当前列名集合核对，主键改名后用新名）
            for (String pk : pkNames) {
                if (indexOfColumn(columns, pk) < 0) {
                    throw new SchemaEvolutionException(
                            "primary key column missing after evolution: " + pk);
                }
            }

            long newVersion = baseVersion + 1;
            Schema newSchema = new Schema(base.schema().version() + 1, columns, pkNames);
            String snapshotId = newSnapshotId();
            // 结构变更不触碰数据文件，文件列表原样继承；行值按列 ID 解释
            Snapshot next = new Snapshot(newVersion, snapshotId, baseVersion, base.snapshotId(),
                    newSchema, base.files(), SnapshotOperation.SCHEMA_CHANGE,
                    System.currentTimeMillis());
            storage.writeSnapshot(next);
            storage.casHead(baseVersion, newVersion);
            LOG.info("[evolve:accept] table={} baseVersion={} committedVersion={} snapshotId={} schemaVersion={} changes={} primaryKey={} basis=headCAS(base={})",
                    tableName, baseVersion, newVersion, snapshotId, newSchema.version(),
                    request.changes().stream().map(c -> c.kind() + ":" + c.columnName()
                            + (c.kind() == com.github.lisama569.lakehousetableformatjava.model.ColumnChange.Kind.RENAME
                                    ? "->" + c.newName() : "")).toList(),
                    newSchema.primaryKey(), baseVersion);
            return next;
        }
    }

    private static int indexOfColumn(List<Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Snapshot compact() {
        synchronized (commitLock) {
            long baseVersion = storage.readHeadVersion();
            Snapshot base = storage.readSnapshot(baseVersion);
            if (base.files().size() <= 1) {
                LOG.info("[compact:skip] table={} version={} snapshotId={} basis=singleOrNoFile",
                        tableName, baseVersion, base.snapshotId());
                return base;
            }

            // 按与查询完全相同的合并规则得到有效行，重写为单个新文件。
            // 不删除任何旧文件：它们仍被历史快照引用，旧版本继续可一致复现。
            TableData data = TableData.load(storage, base);
            List<Row> merged = data.liveRows();
            long maxCommit = base.files().stream()
                    .mapToLong(DataFileRef::maxCommitVersion)
                    .max()
                    .orElse(baseVersion);
            long newVersion = baseVersion + 1;
            DataFileRef rewritten = storage.writeDataFile(newVersion + "-compact.json", merged, maxCommit);

            String snapshotId = newSnapshotId();
            Snapshot next = new Snapshot(newVersion, snapshotId, baseVersion, base.snapshotId(),
                    base.schema(), List.of(rewritten), SnapshotOperation.COMPACTION,
                    System.currentTimeMillis());
            storage.writeSnapshot(next);
            storage.casHead(baseVersion, newVersion);
            long beforeRecords = base.files().stream().mapToLong(DataFileRef::recordCount).sum();
            LOG.info("[compact:accept] table={} baseVersion={} committedVersion={} snapshotId={} filesBefore={} recordsIncludingOverwritten={} liveRecords={} basis=headCAS(base={})",
                    tableName, baseVersion, newVersion, snapshotId, base.files().size(),
                    beforeRecords, merged.size(), baseVersion);
            return next;
        }
    }

    @Override
    public RetentionResult expireSnapshots(RetentionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("retention request must not be null");
        }
        // 与提交共用表级锁：回收期间不会有新提交穿插，扫描到的 HEAD 与
        // 引用集合在删除完成前保持有效，不会删掉刚被新快照引用的文件。
        synchronized (commitLock) {
            long headVersion = storage.readHeadVersion();
            Snapshot head = storage.readSnapshot(headVersion);
            List<Snapshot> all = storage.readAllSnapshots(); // 按版本升序

            // 计算保留下界：keepLastSnapshots 取倒数第 N 个快照的版本；
            // retainFromVersion 直接作为下界。头快照无条件保留。
            long minRetainedVersion;
            if (request.keepLastSnapshots() != null) {
                int n = request.keepLastSnapshots();
                minRetainedVersion = all.size() <= n
                        ? Long.MIN_VALUE
                        : all.get(all.size() - n).version();
            } else {
                minRetainedVersion = Math.min(request.retainFromVersion(), headVersion);
            }

            List<Snapshot> retained = new ArrayList<>();
            List<Snapshot> expired = new ArrayList<>();
            for (Snapshot s : all) {
                if (s.version() >= minRetainedVersion || s.version() == headVersion) {
                    retained.add(s);
                } else {
                    expired.add(s);
                }
            }

            // 被任一保留快照引用的文件一个都不能删；其余数据文件可回收。
            Set<String> referenced = new HashSet<>();
            for (Snapshot s : retained) {
                for (DataFileRef f : s.files()) {
                    referenced.add(f.path());
                }
            }
            List<String> deletedFiles = new ArrayList<>();
            List<String> retainedFiles = new ArrayList<>();
            for (String path : storage.listDataFiles()) {
                if (referenced.contains(path)) {
                    retainedFiles.add(path);
                } else {
                    deletedFiles.add(path);
                }
            }

            // 先清过期快照文件，再删孤儿数据文件；任一步中断都不会让
            // 保留快照（含 HEAD）指向不存在的文件。
            for (Snapshot s : expired) {
                storage.deleteSnapshot(s.version());
            }
            for (String path : deletedFiles) {
                storage.deleteDataFile(path);
            }

            RetentionResult result = new RetentionResult(headVersion, head.snapshotId(),
                    expired.stream().map(Snapshot::version).toList(),
                    retained.stream().map(Snapshot::version).toList(),
                    deletedFiles, retainedFiles);
            LOG.info("[retention:done] table={} headVersion={} headSnapshotId={} policy={} expiredSnapshots={} retainedSnapshots={} deletedFiles={} retainedFiles={} basis=commitLock+headReferenced",
                    tableName, headVersion, head.snapshotId(),
                    request.keepLastSnapshots() != null
                            ? "keepLastSnapshots=" + request.keepLastSnapshots()
                            : "retainFromVersion=" + request.retainFromVersion(),
                    result.expiredSnapshotVersions(), result.retainedSnapshotVersions(),
                    deletedFiles.size(), retainedFiles.size());
            return result;
        }
    }

    @Override
    public List<Map<String, Object>> readRows(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        TableData data = TableData.load(storage, snapshot);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row row : data.liveRows()) {
            // 严格按该快照的 schema 投影：以列 ID 从数据文件行取值。
            // 列改名后 ID 不变，旧数据自动以新名字呈现；被删列不在 schema 中，
            // 不会被读出，也不可能错列到其它列上。
            Map<String, Object> projected = new LinkedHashMap<>();
            for (Column col : snapshot.schema().columns()) {
                Object value;
                if (row.values().containsKey(col.id())) {
                    value = row.values().get(col.id()); // 显式写入的值（含显式 null）
                } else {
                    // 该列在这行写入之后才新增：使用声明缺省值，没有则为 null
                    value = col.defaultValue() == null ? null
                            : col.type().parseDefault(col.defaultValue());
                }
                projected.put(col.name(), value);
            }
            out.add(projected);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> readAt(long version) {
        return readRows(snapshotAt(version));
    }

    @Override
    public List<Map<String, Object>> readLatest() {
        return readRows(currentSnapshot());
    }
}
