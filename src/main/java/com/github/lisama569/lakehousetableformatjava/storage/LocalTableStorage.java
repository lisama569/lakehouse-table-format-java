package com.github.lisama569.lakehousetableformatjava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.lakehousetableformatjava.model.DataFileRef;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于本地文件系统的 {@link TableStorage} 实现。
 *
 * <p>表目录布局：
 * <pre>
 *   table-dir/
 *     metadata/HEAD                 当前头指针版本（纯文本，原子替换）
 *     metadata/snap-{version}.json  不可变快照（内嵌完整 schema）
 *     data/{version}-{seq}.json     不可变数据文件（行按列 ID 存储）
 * </pre>
 * 所有数据文件与快照文件只增不改；compaction 产生新文件，旧文件继续被历史快照引用。
 */
public final class LocalTableStorage implements TableStorage {

    private static final String HEAD_FILE = "metadata/HEAD";
    private static final ObjectMapper MAPPER = JsonCodec.mapper();
    private static final TypeReference<List<Row>> ROW_LIST = new TypeReference<>() {
    };

    private final Path tableDir;

    public LocalTableStorage(Path tableDir) {
        this.tableDir = tableDir;
        try {
            Files.createDirectories(tableDir.resolve("metadata"));
            Files.createDirectories(tableDir.resolve("data"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize table dir " + tableDir, e);
        }
    }

    @Override
    public void writeSnapshot(Snapshot snapshot) {
        Path target = snapshotPath(snapshot.version());
        if (Files.exists(target)) {
            // 版本号单调且在提交锁内分配，走到这里说明出现了编程错误
            throw new IllegalStateException("snapshot file already exists: " + target);
        }
        writeJsonAtomically(target, snapshot);
    }

    @Override
    public Snapshot readSnapshot(long version) {
        Path target = snapshotPath(version);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("snapshot version not found: " + version);
        }
        return readJson(target, Snapshot.class);
    }

    @Override
    public List<Snapshot> readAllSnapshots() {
        Path metaDir = tableDir.resolve("metadata");
        try (Stream<Path> stream = Files.list(metaDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("snap-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> readJson(p, Snapshot.class))
                    .sorted(Comparator.comparingLong(Snapshot::version))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list snapshots", e);
        }
    }

    @Override
    public void casHead(long expectedHeadVersion, long newHeadVersion) {
        long current = readHeadVersion();
        if (current != expectedHeadVersion) {
            // 不覆盖已提交结果：头指针已被其它提交推进
            throw new com.github.lisama569.lakehousetableformatjava.exception.ConflictException(
                    tableDir.getFileName().toString(), expectedHeadVersion, current);
        }
        Path head = tableDir.resolve(HEAD_FILE);
        Path tmp = tableDir.resolve("metadata/HEAD.tmp." + newHeadVersion);
        try {
            Files.writeString(tmp, Long.toString(newHeadVersion), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, head, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, head, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot update HEAD", e);
        }
    }

    @Override
    public long readHeadVersion() {
        Path head = tableDir.resolve(HEAD_FILE);
        if (!Files.exists(head)) {
            return 0L;
        }
        try {
            return Long.parseLong(Files.readString(head, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read HEAD", e);
        }
    }

    @Override
    public DataFileRef writeDataFile(String fileName, List<Row> rows, long maxCommitVersion) {
        Path target = tableDir.resolve("data").resolve(fileName);
        writeJsonAtomically(target, rows);
        return new DataFileRef("data/" + fileName, rows.size(), maxCommitVersion);
    }

    @Override
    public List<Row> readDataFile(String path) {
        Path target = path.startsWith("data/") ? tableDir.resolve(path) : tableDir.resolve("data").resolve(path);
        return readJson(target, ROW_LIST);
    }

    @Override
    public List<String> listDataFiles() {
        Path dataDir = tableDir.resolve("data");
        try (Stream<Path> stream = Files.list(dataDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> "data/" + p.getFileName())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list data files", e);
        }
    }

    @Override
    public void deleteDataFile(String path) {
        Path target = path.startsWith("data/") ? tableDir.resolve(path) : tableDir.resolve("data").resolve(path);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete data file " + target, e);
        }
    }

    @Override
    public void deleteSnapshot(long version) {
        Path target = snapshotPath(version);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete snapshot " + target, e);
        }
    }

    private Path snapshotPath(long version) {
        return tableDir.resolve("metadata").resolve("snap-" + version + ".json");
    }

    private static void writeJsonAtomically(Path target, Object value) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, value);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    private static <T> T readJson(Path path, Class<T> type) {
        try {
            return MAPPER.readValue(Files.readAllBytes(path), type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static <T> T readJson(Path path, TypeReference<T> type) {
        try {
            return MAPPER.readValue(Files.readAllBytes(path), type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
