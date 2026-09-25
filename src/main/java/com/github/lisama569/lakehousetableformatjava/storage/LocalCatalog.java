package com.github.lisama569.lakehousetableformatjava.storage;

import com.github.lisama569.lakehousetableformatjava.exception.TableAlreadyExistsException;
import com.github.lisama569.lakehousetableformatjava.exception.TableNotFoundException;
import com.github.lisama569.lakehousetableformatjava.model.Column;
import com.github.lisama569.lakehousetableformatjava.model.ColumnSpec;
import com.github.lisama569.lakehousetableformatjava.model.DataType;
import com.github.lisama569.lakehousetableformatjava.model.Schema;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.SnapshotOperation;
import com.github.lisama569.lakehousetableformatjava.table.LocalTable;
import com.github.lisama569.lakehousetableformatjava.table.Table;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于本地目录的 catalog。
 *
 * <p>每个表一个子目录；JVM 内以表名为粒度缓存一把提交锁和一个 {@link LocalTable}
 * 实例，保证同一 JVM 内对同一张表的并发提交被串行化，再由 HEAD 的 CAS 兜底。
 */
public final class LocalCatalog implements Catalog {

    private final Path warehouseDir;
    private final ConcurrentHashMap<String, Object> tableLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalTable> tables = new ConcurrentHashMap<>();

    public LocalCatalog(Path warehouseDir) {
        this.warehouseDir = warehouseDir;
        try {
            Files.createDirectories(warehouseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create warehouse dir " + warehouseDir, e);
        }
    }

    @Override
    public Table createTable(String name, List<ColumnSpec> columns, List<String> primaryKey) {
        validateName(name);
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("table must define at least one column");
        }
        List<String> pk = primaryKey == null ? List.of() : List.copyOf(primaryKey);

        Path tableDir = warehouseDir.resolve(name);
        // 目录创建本身充当“表是否已存在”的原子判定
        try {
            Files.createDirectory(tableDir);
        } catch (IOException e) {
            if (Files.isDirectory(tableDir)) {
                throw new TableAlreadyExistsException(name);
            }
            throw new UncheckedIOException("cannot create table dir " + tableDir, e);
        }

        // 构造 schema v1：列 ID 从 1 开始按声明顺序分配，稳定不复用
        List<Column> built = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int nextId = 1;
        for (ColumnSpec spec : columns) {
            if (spec.name() == null || spec.name().isBlank()) {
                throw new IllegalArgumentException("column name must not be blank");
            }
            if (!names.add(spec.name())) {
                throw new IllegalArgumentException("duplicate column name: " + spec.name());
            }
            DataType type = spec.type() == null ? DataType.STRING : spec.type();
            built.add(new Column(nextId++, spec.name(), type, spec.nullable(), spec.defaultValue()));
        }
        for (String pkCol : pk) {
            if (names.stream().noneMatch(n -> n.equals(pkCol))) {
                throw new IllegalArgumentException("primary key column not defined: " + pkCol);
            }
        }

        TableStorage storage = new LocalTableStorage(tableDir);
        Schema schema = new Schema(1, built, pk);
        // 初始空快照 v0：没有父版本，查询历史时可一致复现“空表”
        Snapshot initial = new Snapshot(
                0L,
                UUID.randomUUID().toString(),
                -1L,
                null,
                schema,
                List.of(),
                SnapshotOperation.INIT,
                System.currentTimeMillis());
        storage.writeSnapshot(initial);
        // 初始提交没有父版本：HEAD 文件尚不存在时直接原子建立，避免与“版本 0”混淆
        try {
            Path headFile = tableDir.resolve("metadata/HEAD");
            Path headTmp = tableDir.resolve("metadata/HEAD.init.tmp");
            Files.writeString(headTmp, "0", java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(headTmp, headFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(headTmp, headFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize HEAD", e);
        }

        Object lock = tableLocks.computeIfAbsent(name, k -> new Object());
        LocalTable table = new LocalTable(name, storage, lock);
        tables.put(name, table);
        return table;
    }

    @Override
    public Table openTable(String name) {
        validateName(name);
        LocalTable cached = tables.get(name);
        if (cached != null) {
            return cached;
        }
        Path tableDir = warehouseDir.resolve(name);
        if (!Files.isDirectory(tableDir.resolve("metadata"))) {
            throw new TableNotFoundException(name);
        }
        TableStorage storage = new LocalTableStorage(tableDir);
        Object lock = tableLocks.computeIfAbsent(name, k -> new Object());
        LocalTable table = new LocalTable(name, storage, lock);
        LocalTable existing = tables.putIfAbsent(name, table);
        return existing != null ? existing : table;
    }

    @Override
    public void dropTable(String name) {
        validateName(name);
        Path tableDir = warehouseDir.resolve(name);
        if (!Files.isDirectory(tableDir)) {
            throw new TableNotFoundException(name);
        }
        try (Stream<Path> walk = Files.walk(tableDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot drop table " + name, e);
        }
        tables.remove(name);
        tableLocks.remove(name);
    }

    @Override
    public List<String> listTables() {
        try (Stream<Path> stream = Files.list(warehouseDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isDirectory(p.resolve("metadata")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list tables", e);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("..")) {
            throw new IllegalArgumentException("invalid table name: " + name);
        }
    }
}
