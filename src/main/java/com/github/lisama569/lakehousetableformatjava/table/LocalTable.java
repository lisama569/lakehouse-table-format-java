package com.github.lisama569.lakehousetableformatjava.table;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.exception.SchemaEvolutionException;
import com.github.lisama569.lakehousetableformatjava.model.Column;
import com.github.lisama569.lakehousetableformatjava.model.ColumnChange;
import com.github.lisama569.lakehousetableformatjava.model.DataFileRef;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionPolicy;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Schema;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.SnapshotOperation;
import com.github.lisama569.lakehousetableformatjava.model.VacuumResult;
import com.github.lisama569.lakehousetableformatjava.model.WriteMode;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;
import com.github.lisama569.lakehousetableformatjava.storage.TableStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
            // 记录本次请求内的改名（旧名 -> 新名），主键名随之更新；数据按列 ID 寻址，
            // 所以主键改名与普通列改名一样不会错列
            Map<String, String> renames = new LinkedHashMap<>();

            for (ColumnChange change : request.changes()) {
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
                        if (spec == null || spec.name() == null || spec.name().isBlank()) {
                            throw new SchemaEvolutionException("ADD requires a valid column spec");
                        }
                        if (indexOfColumn(columns, spec.name()) >= 0) {
                            throw new SchemaEvolutionException(
                                    "cannot add column, name already exists: " + spec.name());
                        }
                        var type = spec.type() == null
                                ? DataType.STRING
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
                        if (idx < 0) {
                            throw new SchemaEvolutionException(
                                    "cannot rename, column not found: " + change.columnName());
                        }
                        if (change.newName() == null || change.newName().isBlank()) {
                            throw new SchemaEvolutionException("RENAME requires a non-blank new name");
                        }
                        if (indexOfColumn(columns, change.newName()) >= 0) {
                            throw new SchemaEvolutionException(
                                    "cannot rename, target name already exists: " + change.newName());
                        }
                        Column oldCol = columns.get(idx);
                        // 关键：列 ID 保持不变，只改名字，历史文件按 ID 不会错列
                        columns.set(idx, new Column(oldCol.id(), change.newName(), oldCol.type(),
                                oldCol.nullable(), oldCol.defaultValue()));
                        renames.put(change.columnName(), change.newName());
                    }
                    case DROP -> {
                        if (idx < 0) {
                            throw new SchemaEvolutionException(
                                    "cannot drop, column not found: " + change.columnName());
                        }
                        if (base.schema().primaryKey().contains(change.columnName())) {
                            throw new SchemaEvolutionException(
                                    "cannot drop a primary key column: " + change.columnName());
                        }
                        columns.remove(idx);
                        // ID 不复用：nextId 已在分配时单调推进
                    }
                }
            }

            // 演进后主键列必须仍然存在（按列 ID 校验，名字可已被 RENAME 更新）
            List<String> newPrimaryKey = new ArrayList<>();
            for (String pk : base.schema().primaryKey()) {
                String pkName = renames.getOrDefault(pk, pk);
                if (indexOfColumn(columns, pkName) < 0) {
                    throw new SchemaEvolutionException(
                            "primary key column missing after evolution: " + pk);
                }
                newPrimaryKey.add(pkName);
            }

            long newVersion = baseVersion + 1;
            Schema newSchema = new Schema(base.schema().version() + 1, columns,
                    newPrimaryKey);
            String snapshotId = newSnapshotId();
            // 结构变更不触碰数据文件，文件列表原样继承；行值按列 ID 解释
            Snapshot next = new Snapshot(newVersion, snapshotId, baseVersion, base.snapshotId(),
                    newSchema, base.files(), SnapshotOperation.SCHEMA_CHANGE,
                    System.currentTimeMillis());
            storage.writeSnapshot(next);
            storage.casHead(baseVersion, newVersion);
            LOG.info("[evolve:accept] table={} baseVersion={} committedVersion={} snapshotId={} schemaVersion={} changes={} basis=headCAS(base={})",
                    tableName, baseVersion, newVersion, snapshotId, newSchema.version(),
                    request.changes().stream().map(c -> c.kind() + ":" + c.columnName()).toList(),
                    baseVersion);
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
    public WriteResult delete(DeleteRequest request) {
        if (request == null || request.keys() == null) {
            throw new IllegalArgumentException("delete request and keys must not be null");
        }
        if (request.keys().isEmpty()) {
            throw new IllegalArgumentException("delete batch must contain at least one key");
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
            // 整批校验（不存在/重复/缺主键/多余列各自可区分），有任何问题不产生副作用
            RecordValidator.Validated validated =
                    RecordValidator.validateDelete(base.schema(), request.keys(), data.keys());
            if (!validated.ok()) {
                LOG.info("[delete:reject] table={} baseVersion={} rejectedCount={} reasons={}",
                        tableName, baseVersion, validated.rejected().size(),
                        validated.rejected().stream().map(r -> r.reason().name()).toList());
                return new WriteResult(false, null, null, 0L, validated.rejected());
            }

            // 删除 = 追加一个只含墓碑行的新不可变文件；旧文件原样保留给历史快照。
            // 墓碑与普通行走同一来源坐标合并规则：坐标更大的墓碑遮蔽旧行；
            // 删除之后再次写入同主键，新行坐标更大，确定地重新可见。
            long newVersion = baseVersion + 1;
            DataFileRef tombstones = storage.writeDataFile(
                    newVersion + "-delete.json", validated.rows(), newVersion);
            List<DataFileRef> newFiles = new ArrayList<>(base.files());
            newFiles.add(tombstones);

            String snapshotId = newSnapshotId();
            Snapshot next = new Snapshot(newVersion, snapshotId, baseVersion, base.snapshotId(),
                    base.schema(), List.copyOf(newFiles), SnapshotOperation.DELETE,
                    System.currentTimeMillis());
            storage.writeSnapshot(next);
            storage.casHead(baseVersion, newVersion);
            LOG.info("[delete:accept] table={} baseVersion={} committedVersion={} snapshotId={} deleted={} filesAfter={} basis=tombstoneFile+headCAS(base={})",
                    tableName, baseVersion, newVersion, snapshotId, validated.rows().size(),
                    newFiles.size(), baseVersion);
            return new WriteResult(true, newVersion, snapshotId, validated.rows().size(), List.of());
        }
    }

    @Override
    public VacuumResult vacuum(
            RetentionPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("retention policy must not be null");
        }
        boolean byVersion = policy.minVersion() != null;
        boolean byCount = policy.retainLastSnapshots() != null;
        if (byVersion == byCount) {
            throw new IllegalArgumentException(
                    "exactly one of minVersion / retainLastSnapshots must be specified");
        }
        if (byCount && policy.retainLastSnapshots() < 1) {
            throw new IllegalArgumentException("retainLastSnapshots must be >= 1");
        }
        synchronized (commitLock) {
            long headAtStart = storage.readHeadVersion();
            if (byVersion && (policy.minVersion() < 0 || policy.minVersion() > headAtStart)) {
                throw new IllegalArgumentException("minVersion out of range [0, " + headAtStart + "]: "
                        + policy.minVersion());
            }
            List<Snapshot> all = storage.readAllSnapshots();

            // 1) 计算保留 / 过期快照集合（头快照一定在保留集合中）
            List<Snapshot> retained;
            if (byVersion) {
                retained = all.stream()
                        .filter(s -> s.version() >= policy.minVersion())
                        .toList();
            } else {
                int n = policy.retainLastSnapshots();
                int from = Math.max(0, all.size() - n);
                retained = all.subList(from, all.size());
            }
            Set<Long> retainedVersions = retained.stream().map(Snapshot::version)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Snapshot> expired = all.stream()
                    .filter(s -> !retainedVersions.contains(s.version()))
                    .toList();

            // 2) 引用闭包：保留快照引用的文件一律保留；只有“被过期快照引用、
            //    且不被任何保留快照引用”的文件才允许物理删除。
            //    数据文件名按提交版本分配、永不复用，而新快照只能继承父快照
            //    文件列表或引用更新版本的新文件，所以回收期间即使有跨进程并发
            //    提交把 HEAD 推进，新快照引用的文件也绝不可能落入这个删除集合。
            Set<String> retainedPaths = new LinkedHashSet<>();
            for (Snapshot s : retained) {
                for (DataFileRef f : s.files()) {
                    retainedPaths.add(f.path());
                }
            }
            Set<String> expiredOnlyPaths = new LinkedHashSet<>();
            for (Snapshot s : expired) {
                for (DataFileRef f : s.files()) {
                    if (!retainedPaths.contains(f.path())) {
                        expiredOnlyPaths.add(f.path());
                    }
                }
            }

            // 3) 先删过期快照文件，再删数据文件：中断时只可能留下无人引用的
            //    垃圾文件，绝不可能让仍存在的快照指向已删除的文件。
            for (Snapshot s : expired) {
                storage.deleteSnapshotFile(s.version());
            }
            List<String> deletedFiles = new ArrayList<>();
            for (String path : expiredOnlyPaths.stream().sorted().toList()) {
                storage.deleteDataFile(path);
                deletedFiles.add(path);
            }

            long headAtEnd = storage.readHeadVersion();
            List<Long> expiredVersions = expired.stream().map(Snapshot::version)
                    .sorted().toList();
            List<Long> keptVersions = retained.stream().map(Snapshot::version)
                    .sorted().toList();
            List<String> keptFiles = retainedPaths.stream().sorted().toList();
            LOG.info("[vacuum:accept] table={} headStart={} headEnd={} basis={} retainedSnapshots={} expiredSnapshots={} retainedFiles={} deletedFiles={} concurrentCommit={}",
                    tableName, headAtStart, headAtEnd,
                    byVersion ? "minVersion=" + policy.minVersion()
                            : "retainLast=" + policy.retainLastSnapshots(),
                    keptVersions, expiredVersions, keptFiles.size(), deletedFiles,
                    headAtEnd != headAtStart);
            return new VacuumResult(
                    headAtStart, headAtEnd, keptVersions, expiredVersions,
                    List.copyOf(deletedFiles), keptFiles);
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
