package com.github.lisama569.lakehousetableformatjava.lakehouse.table;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitReport;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RowRejection;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.evolve.SchemaChange;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.ColumnDef;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.Row;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.TableSchema;
import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.DataFileMeta;
import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.OperationType;
import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.Snapshot;
import com.github.lisama569.lakehousetableformatjava.lakehouse.storage.DataFileRecord;
import com.github.lisama569.lakehousetableformatjava.lakehouse.storage.LocalFileIO;
import com.github.lisama569.lakehousetableformatjava.lakehouse.storage.SchemaFile;
import com.github.lisama569.lakehousetableformatjava.lakehouse.storage.StoredDataFile;
import com.github.lisama569.lakehousetableformatjava.lakehouse.storage.TableStateFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于本地文件系统（JSON 元数据 + 不可变数据文件）的表实现。
 *
 * <p>提交协议（在每张表的 JVM 锁内串行执行）：
 * 读最新根指针 -> 校验 baseVersion 是否仍为最新（OCC，过期则 STALE_SNAPSHOT 拒绝）
 * -> 校验输入行 -> 写新的不可变数据文件 -> 写快照清单与根指针（原子替换）。
 * 数据文件一旦写入永不修改，历史快照引用固定的文件集合，因此旧版本可被一致复现。</p>
 */
final class LocalTable implements Table {

    private static final Logger log = LoggerFactory.getLogger(LocalTable.class);

    private final LocalFileIO io;
    private volatile TableStateFile state;
    private volatile List<Snapshot> snapshots = List.of();
    /** schema 只增不改，可直接并发累积。 */
    private final Map<Long, TableSchema> schemas = new java.util.concurrent.ConcurrentHashMap<>();
    /** 快照索引：构建不可变 Map 后整体替换，绝不在共享实例上原地 clear/put，避免并发读者读到空窗。 */
    private volatile Map<Long, Snapshot> snapshotByVersion = Map.of();

    private LocalTable(LocalFileIO io) {
        this.io = io;
    }

    /** 加载磁盘上已存在的表。 */
    static LocalTable load(Path tableDir) {
        LocalFileIO fileIO = new LocalFileIO(tableDir);
        LocalTable table = new LocalTable(fileIO);
        table.reload();
        log.info("加载表 {}，当前版本 v{}，快照数 {}",
                table.name(), table.currentVersion(), table.snapshots.size());
        return table;
    }

    /** 创建新表：持久化 schema v0 与 v0 初始化快照。 */
    static LocalTable create(Path tableDir, String tableName, List<ColumnDef> columns, List<Integer> keyColumnIds) {
        LocalFileIO fileIO = new LocalFileIO(tableDir);
        fileIO.ensureLayout();
        if (fileIO.stateExists()) {
            throw new CommitRejectedException(RejectReason.TABLE_ALREADY_EXISTS,
                    "表已存在: " + tableName);
        }
        LocalTable table = new LocalTable(fileIO);
        table.name0 = tableName;
        TableSchema schema = new TableSchema(0, columns.size(), new ArrayList<>(columns), keyColumnIds);
        table.schemas.put(0L, schema);
        table.persistSchema(schema);

        TableStateFile st = new TableStateFile();
        st.setTableName(tableName);
        st.setNextColumnId(columns.size());
        st.setKeyColumns(schema.getKeyColumnIds().stream().map(schema::findById).toList());
        st.setCurrentVersion(0L);
        TableStateFile.SnapshotEntry entry = new TableStateFile.SnapshotEntry();
        entry.version = 0;
        entry.snapshotId = newSnapshotId();
        entry.parentVersion = null;
        entry.schemaId = 0;
        entry.sequenceNumber = 0;
        entry.operation = OperationType.INITIALIZE.name();
        entry.dataFiles = List.of();
        entry.committedAt = System.currentTimeMillis();
        entry.summary = "初始化表，列数=%d，主键列数=%d".formatted(columns.size(), keyColumnIds.size());
        st.setSnapshots(new ArrayList<>(List.of(entry)));
        fileIO.writeState(st);

        table.state = st;
        table.rebuildViews();
        log.info("[commit] table={} v{} snapshot={} op=INITIALIZE 判定依据=建表初始化",
                tableName, entry.version, entry.snapshotId);
        return table;
    }

    private String name0;

    private void reload() {
        this.state = io.readState();
        this.name0 = state.getTableName();
        for (TableStateFile.SnapshotEntry entry : state.getSnapshots()) {
            if (!schemas.containsKey(entry.schemaId)) {
                SchemaFile sf = io.readJson(io.schemaFile(entry.schemaId), SchemaFile.class);
                schemas.put(sf.getSchemaId(),
                        new TableSchema(sf.getSchemaId(), sf.getNextColumnId(), sf.getColumns(), sf.getKeyColumnIds()));
            }
        }
        rebuildViews();
    }

    /** 先在局部构建完整的新清单与索引，再通过 volatile 引用一次性发布（无空窗、无需在原 Map 上 clear）。 */
    private void rebuildViews() {
        List<Snapshot> list = new ArrayList<>();
        Map<Long, Snapshot> byVersion = new LinkedHashMap<>();
        for (TableStateFile.SnapshotEntry e : state.getSnapshots()) {
            Snapshot snap = new Snapshot(e.version, e.snapshotId, e.parentVersion, e.schemaId,
                    e.sequenceNumber, OperationType.valueOf(e.operation), e.dataFiles, e.committedAt, e.summary);
            list.add(snap);
            byVersion.put(e.version, snap);
        }
        // 先发布索引、再发布列表：读者一旦从列表读到新版本号，索引必然已包含该版本，
        // 因此锁外并发读取不会看到"版本号新但索引缺失"的空窗。
        this.snapshotByVersion = Map.copyOf(byVersion);
        this.snapshots = List.copyOf(list);
    }

    @Override
    public String name() {
        return name0;
    }

    /** 当前已发布版本号：直接取自已原子发布的不可变快照清单，避免读到正在改写的 state 字段。 */
    private long currentVersion() {
        List<Snapshot> published = this.snapshots;
        return published.isEmpty() ? 0L : published.get(published.size() - 1).getVersion();
    }

    @Override
    public Snapshot currentSnapshot() {
        return snapshotByVersion.get(currentVersion());
    }

    @Override
    public Snapshot snapshotAt(long version) {
        Snapshot snap = snapshotByVersion.get(version);
        if (snap == null) {
            throw new CommitRejectedException(RejectReason.SNAPSHOT_NOT_FOUND,
                    "快照版本不存在: v%d（当前最新 v%d）".formatted(version, currentVersion()));
        }
        return snap;
    }

    @Override
    public TableSchema schemaAt(long version) {
        return schemas.get(snapshotAt(version).getSchemaId());
    }

    @Override
    public List<Snapshot> listSnapshots() {
        return snapshots;
    }

    @Override
    public CommitReport write(long baseVersion, WriteMode mode, List<Map<String, Object>> rows) {
        Objects.requireNonNull(mode, "mode");
        rows = rows == null ? List.of() : rows;
        io.tableLock().lock();
        try {
            long latest = currentVersion();
            if (snapshotByVersion.get(baseVersion) == null) {
                throw new CommitRejectedException(RejectReason.SNAPSHOT_NOT_FOUND,
                        "baseVersion 不存在: v%d（当前最新 v%d）".formatted(baseVersion, latest));
            }
            if (baseVersion != latest) {
                Snapshot head = snapshotByVersion.get(latest);
                throw new CommitRejectedException(RejectReason.STALE_SNAPSHOT,
                        "基于过期快照 v%d 的%s写入被拒绝：当前最新为 v%d（snapshot=%s, op=%s）。"
                                .formatted(baseVersion, mode, latest, head.getSnapshotId(), head.getOperation()));
            }
            TableSchema baseSchema = schemas.get(currentSnapshot().getSchemaId());
            RowValidator validator = new RowValidator(baseSchema);
            List<RowRejection> problems = validator.validate(rows);
            if (!problems.isEmpty()) {
                log.info("[reject] table={} op={} base=v{} 判定依据=输入校验失败，非法记录 {} 条（整批拒绝）",
                        name0, mode, baseVersion, problems.size());
                throw new CommitRejectedException(
                        problems.get(0).reason(),
                        "写入被拒绝：存在 %d 条非法记录（不做部分写入）".formatted(problems.size()),
                        problems);
            }

            // 批内确定性去重：同一批次重复主键 -> 后者覆盖前者（保留最后一条），并计数上报
            Map<String, RowValidator.NormalizedRow> deduped = new LinkedHashMap<>();
            for (Map<String, Object> raw : rows) {
                RowValidator.NormalizedRow nr = validator.normalize(raw);
                deduped.put(nr.key, nr);
            }
            long duplicatesInBatch = rows.size() - deduped.size();

            Map<String, Long> existingKeyAtSequence = collectLatestKeysWithSequence(currentSnapshot());
            if (mode == WriteMode.APPEND) {
                List<RowRejection> collisions = new ArrayList<>();
                java.util.Set<String> reported = new java.util.HashSet<>();
                for (int idx = 0; idx < rows.size(); idx++) {
                    RowValidator.NormalizedRow nr = validator.normalize(rows.get(idx));
                    Long existsSeq = existingKeyAtSequence.get(nr.key);
                    if (existsSeq != null && reported.add(nr.key)) {
                        collisions.add(new RowRejection(idx, nr.key, RejectReason.PRIMARY_KEY_ALREADY_EXISTS,
                                "APPEND 模式：主键在基线快照 v%d（数据文件 seq=%d）中已存在"
                                        .formatted(baseVersion, existsSeq)));
                    }
                }
                if (!collisions.isEmpty()) {
                    log.info("[reject] table={} op=APPEND base=v{} 判定依据=主键冲突 {} 个",
                            name0, baseVersion, collisions.size());
                    throw new CommitRejectedException(RejectReason.PRIMARY_KEY_ALREADY_EXISTS,
                            "APPEND 写入被拒绝：%d 个主键在基线快照中已存在".formatted(collisions.size()),
                            collisions);
                }
            }

            long newVersion = latest + 1;
            long newSequence = currentSnapshot().getSequenceNumber() + 1;
            String newSnapshotId = newSnapshotId();
            Path filePath = io.dataFile(newSequence, newSnapshotId);
            List<DataFileRecord> records = new ArrayList<>();
            for (RowValidator.NormalizedRow nr : deduped.values()) {
                records.add(new DataFileRecord(nr.key, nr.valuesById));
            }
            StoredDataFile stored = new StoredDataFile(
                    io.tableDir().relativize(filePath).toString(), newSequence, records);
            io.writeDataFile(filePath, stored);

            DataFileMeta meta = new DataFileMeta(stored.getPath(), records.size(),
                    new java.util.TreeSet<>(deduped.keySet()), newSequence);

            Snapshot head = currentSnapshot();
            List<DataFileMeta> files = new ArrayList<>(head.getSnapshotDataFiles());
            files.add(meta);

            String basis = mode == WriteMode.APPEND
                    ? "基线=v%d 无主键冲突（%d 个键均为新键），批内重复 %d 条取末值"
                            .formatted(baseVersion, deduped.size(), duplicatesInBatch)
                    : "基线=v%d MERGE 覆盖/插入（%d 个键），批内重复 %d 条取末值，冲突按 seq 高者胜"
                            .formatted(baseVersion, deduped.size(), duplicatesInBatch);
            Snapshot committed = appendSnapshotEntry(head, newVersion, newSnapshotId, head.getSchemaId(),
                    newSequence, mode == WriteMode.APPEND ? OperationType.APPEND : OperationType.MERGE,
                    files, "%s：接受 %d 行，批内重复 %d 条（末值生效）"
                            .formatted(mode, records.size(), duplicatesInBatch));

            log.info("[commit] table={} v{} snapshot={} op={} seq={} 判定依据={}",
                    name0, newVersion, newSnapshotId, committed.getOperation(), newSequence, basis);
            return new CommitReport(name0, newVersion, newSnapshotId, latest, head.getSchemaId(),
                    committed.getOperation(), newSequence, records.size(), duplicatesInBatch, basis);
        } finally {
            io.tableLock().unlock();
        }
    }

    @Override
    public CommitReport evolve(long baseVersion, Collection<SchemaChange> changes) {
        List<SchemaChange> changeList = changes == null ? List.of() : List.copyOf(changes);
        io.tableLock().lock();
        try {
            long latest = currentVersion();
            if (snapshotByVersion.get(baseVersion) == null) {
                throw new CommitRejectedException(RejectReason.SNAPSHOT_NOT_FOUND,
                        "baseVersion 不存在: v%d（当前最新 v%d）".formatted(baseVersion, latest));
            }
            if (baseVersion != latest) {
                Snapshot head = snapshotByVersion.get(latest);
                throw new CommitRejectedException(RejectReason.STALE_SNAPSHOT,
                        "基于过期快照 v%d 的结构变更被拒绝：当前最新为 v%d（snapshot=%s, op=%s）。"
                                .formatted(baseVersion, latest, head.getSnapshotId(), head.getOperation()));
            }
            if (changeList.isEmpty()) {
                throw new CommitRejectedException(RejectReason.INVALID_SCHEMA_CHANGE,
                        "结构变更列表为空");
            }
            Snapshot head = currentSnapshot();
            TableSchema baseSchema = schemas.get(head.getSchemaId());
            TableSchema newSchema;
            try {
                newSchema = baseSchema.apply(changeList);
            } catch (IllegalArgumentException ex) {
                log.info("[reject] table={} op=EVOLVE base=v{} 判定依据=非法结构变更: {}",
                        name0, baseVersion, ex.getMessage());
                throw new CommitRejectedException(RejectReason.INVALID_SCHEMA_CHANGE,
                        "结构变更被拒绝: " + ex.getMessage());
            }
            schemas.put(newSchema.getSchemaId(), newSchema);
            persistSchema(newSchema);

            long newVersion = latest + 1;
            long newSequence = head.getSequenceNumber() + 1;
            String newSnapshotId = newSnapshotId();
            Snapshot committed = appendSnapshotEntry(head, newVersion, newSnapshotId, newSchema.getSchemaId(),
                    newSequence, OperationType.SCHEMA_CHANGE, head.getSnapshotDataFiles(),
                    "结构变更 %d 项：%s".formatted(changeList.size(), describeChanges(changeList)),
                    newSchema.getNextColumnId());

            log.info("[commit] table={} v{} snapshot={} op=SCHEMA_CHANGE schema=v{} seq={} 判定依据=基线=v{} 上顺序应用变更 {}",
                    name0, newVersion, newSnapshotId, newSchema.getSchemaId(), newSequence,
                    baseVersion, describeChanges(changeList));
            return new CommitReport(name0, newVersion, newSnapshotId, latest, newSchema.getSchemaId(),
                    OperationType.SCHEMA_CHANGE, newSequence, 0, 0,
                    "基线=v%d，schema v%d->v%d".formatted(baseVersion, baseSchema.getSchemaId(), newSchema.getSchemaId()));
        } finally {
            io.tableLock().unlock();
        }
    }

    @Override
    public CommitReport compact(long baseVersion) {
        io.tableLock().lock();
        try {
            long latest = currentVersion();
            if (snapshotByVersion.get(baseVersion) == null) {
                throw new CommitRejectedException(RejectReason.SNAPSHOT_NOT_FOUND,
                        "baseVersion 不存在: v%d（当前最新 v%d）".formatted(baseVersion, latest));
            }
            if (baseVersion != latest) {
                Snapshot head = snapshotByVersion.get(latest);
                throw new CommitRejectedException(RejectReason.STALE_SNAPSHOT,
                        "基于过期快照 v%d 的整理请求被拒绝：当前最新为 v%d（snapshot=%s, op=%s）。"
                                .formatted(baseVersion, latest, head.getSnapshotId(), head.getOperation()));
            }
            Snapshot head = currentSnapshot();
            Map<String, Map<Integer, Object>> table = resolveLatest(head);

            long newVersion = latest + 1;
            long newSequence = head.getSequenceNumber() + 1;
            String newSnapshotId = newSnapshotId();
            Path filePath = io.dataFile(newSequence, newSnapshotId);
            List<DataFileRecord> records = new ArrayList<>();
            // 确定性顺序：按内部键排序，保证整理结果可复现
            for (String key : new TreeMap<>(table).keySet()) {
                records.add(new DataFileRecord(key, new LinkedHashMap<>(table.get(key))));
            }
            StoredDataFile stored = new StoredDataFile(
                    io.tableDir().relativize(filePath).toString(), newSequence, records);
            io.writeDataFile(filePath, stored);

            DataFileMeta meta = new DataFileMeta(stored.getPath(), records.size(),
                    new java.util.TreeSet<>(table.keySet()), newSequence);

            String basis = "基线=v%d，合并 %d 个数据文件（%d 条去重后记录）为 1 个；旧文件保留以复现历史快照"
                    .formatted(baseVersion, head.getSnapshotDataFiles().size(), records.size());
            Snapshot committed = appendSnapshotEntry(head, newVersion, newSnapshotId, head.getSchemaId(),
                    newSequence, OperationType.COMPACT, List.of(meta),
                    "整理 %d 个数据文件为 1 个，记录数=%d".formatted(head.getSnapshotDataFiles().size(), records.size()));

            log.info("[commit] table={} v{} snapshot={} op=COMPACT seq={} 判定依据={}",
                    name0, newVersion, newSnapshotId, newSequence, basis);
            return new CommitReport(name0, newVersion, newSnapshotId, latest, head.getSchemaId(),
                    OperationType.COMPACT, newSequence, records.size(), 0, basis);
        } finally {
            io.tableLock().unlock();
        }
    }

    @Override
    public Map<String, Map<String, Object>> readAt(long version) {
        Snapshot snap = snapshotAt(version);
        TableSchema schema = schemas.get(snap.getSchemaId());
        Map<String, Map<Integer, Object>> raw = resolveLatest(snap);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        // 按主键排序输出，保证同一快照多次读取结果顺序也一致、可解释
        for (String key : new TreeMap<>(raw).keySet()) {
            out.put(key, Row.project(raw.get(key), schema));
        }
        return out;
    }

    @Override
    public Map<String, Map<String, Object>> readLatest() {
        return readAt(currentVersion());
    }

    // ---- 内部方法 ----

    /** 按数据文件的 addedSequenceNumber 升序回放，同一主键以高 seq（后写）覆盖低 seq。 */
    private Map<String, Map<Integer, Object>> resolveLatest(Snapshot snap) {
        List<DataFileMeta> ordered = new ArrayList<>(snap.getSnapshotDataFiles());
        ordered.sort(java.util.Comparator.comparingLong(DataFileMeta::getAddedSequenceNumber));
        Map<String, Map<Integer, Object>> result = new LinkedHashMap<>();
        for (DataFileMeta meta : ordered) {
            StoredDataFile file = io.readDataFile(io.tableDir().resolve(meta.getPath()));
            for (DataFileRecord rec : file.getRecords()) {
                result.put(rec.getKey(), rec.getValues());
            }
        }
        return result;
    }

    private Map<String, Long> collectLatestKeysWithSequence(Snapshot snap) {
        List<DataFileMeta> ordered = new ArrayList<>(snap.getSnapshotDataFiles());
        ordered.sort(java.util.Comparator.comparingLong(DataFileMeta::getAddedSequenceNumber));
        Map<String, Long> keySeq = new LinkedHashMap<>();
        for (DataFileMeta meta : ordered) {
            for (String key : meta.getPrimaryKeys()) {
                keySeq.put(key, meta.getAddedSequenceNumber());
            }
        }
        return keySeq;
    }

    private Snapshot appendSnapshotEntry(Snapshot parent, long newVersion, String newSnapshotId, long schemaId,
                                         long newSequence, OperationType op, List<DataFileMeta> files,
                                         String summary) {
        return appendSnapshotEntry(parent, newVersion, newSnapshotId, schemaId, newSequence, op, files,
                summary, -1);
    }

    /**
     * 提交点：先在新的 TableStateFile 副本上追加快照并原子落盘，落盘成功后才替换内存中的 state
     * 并发布新视图。落盘失败则内存态完全不变（不会出现"半提交"），随后从磁盘重载并抛出 COMMIT_FAILED。
     * nextColumnId >= 0 时随本次提交一起更新列 id 水位（用于 ADD COLUMN）。
     */
    private Snapshot appendSnapshotEntry(Snapshot parent, long newVersion, String newSnapshotId, long schemaId,
                                         long newSequence, OperationType op, List<DataFileMeta> files,
                                         String summary, int nextColumnId) {
        TableStateFile.SnapshotEntry entry = new TableStateFile.SnapshotEntry();
        entry.version = newVersion;
        entry.snapshotId = newSnapshotId;
        entry.parentVersion = parent.getVersion();
        entry.schemaId = schemaId;
        entry.sequenceNumber = newSequence;
        entry.operation = op.name();
        entry.dataFiles = List.copyOf(files);
        entry.committedAt = System.currentTimeMillis();
        entry.summary = summary;

        TableStateFile candidate = cloneState(state);
        candidate.getSnapshots().add(entry);
        candidate.setCurrentVersion(newVersion);
        if (nextColumnId >= 0) {
            candidate.setNextColumnId(nextColumnId);
        }
        try {
            io.writeState(candidate);
        } catch (RuntimeException ex) {
            try {
                reload();
            } catch (RuntimeException ignored) {
                // 磁盘根指针仍是旧版本，保持现有内存态也可继续提供旧快照读
            }
            throw new CommitRejectedException(RejectReason.COMMIT_FAILED,
                    "提交 v%d 落盘失败，已拒绝该提交（不产生部分写入）: %s"
                            .formatted(newVersion, ex.getMessage()));
        }
        this.state = candidate;
        rebuildViews();
        return snapshotByVersion.get(newVersion);
    }

    private static TableStateFile cloneState(TableStateFile src) {
        TableStateFile copy = new TableStateFile();
        copy.setTableName(src.getTableName());
        copy.setNextColumnId(src.getNextColumnId());
        copy.setKeyColumns(new ArrayList<>(src.getKeyColumns()));
        copy.setCurrentVersion(src.getCurrentVersion());
        copy.setSnapshots(new ArrayList<>(src.getSnapshots()));
        return copy;
    }

    private void persistSchema(TableSchema schema) {
        SchemaFile sf = new SchemaFile();
        sf.setSchemaId(schema.getSchemaId());
        sf.setNextColumnId(schema.getNextColumnId());
        sf.setColumns(schema.getColumns());
        sf.setKeyColumnIds(schema.getKeyColumnIds());
        io.writeJson(io.schemaFile(schema.getSchemaId()), sf);
    }

    private static String newSnapshotId() {
        return UUID.randomUUID().toString();
    }

    private static String describeChanges(List<SchemaChange> changes) {
        return changes.stream().map(c -> switch (c) {
            case SchemaChange.AddColumn a -> "ADD(%s %s%s)".formatted(a.name(), a.type(),
                    a.nullable() ? " NULL" : " NOT NULL DEFAULT ...");
            case SchemaChange.RenameColumn r -> "RENAME(%s->%s)".formatted(r.from(), r.to());
            case SchemaChange.DropColumn d -> "DROP(%s)".formatted(d.name());
        }).collect(Collectors.joining(", "));
    }
}
