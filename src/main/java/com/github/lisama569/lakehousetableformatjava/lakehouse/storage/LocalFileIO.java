package com.github.lisama569.lakehousetableformatjava.lakehouse.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 表目录下全部文件操作的统一入口。 */
public final class LocalFileIO {

    private static final ConcurrentHashMap<String, ReentrantLock> TABLE_LOCKS = new ConcurrentHashMap<>();

    private final Path tableDir;

    public LocalFileIO(Path tableDir) {
        this.tableDir = tableDir;
    }

    public Path tableDir() {
        return tableDir;
    }

    /** JVM 内同一张表的串行化锁：提交（读状态->判定->写文件->换根指针）整体持锁。 */
    public ReentrantLock tableLock() {
        return TABLE_LOCKS.computeIfAbsent(
                tableDir.toAbsolutePath().normalize().toString(), k -> new ReentrantLock());
    }

    public Path metadataDir() {
        return tableDir.resolve("metadata");
    }

    public Path dataDir() {
        return tableDir.resolve("data");
    }

    public void ensureLayout() {
        try {
            Files.createDirectories(metadataDir());
            Files.createDirectories(dataDir());
        } catch (IOException e) {
            throw new UncheckedIOException("初始化表目录失败: " + tableDir, e);
        }
    }

    public Path stateFile() {
        return metadataDir().resolve("table-state.json");
    }

    public Path schemaFile(long schemaId) {
        return metadataDir().resolve("schema-%d.json".formatted(schemaId));
    }

    public Path dataFile(long sequenceNumber, String uuid) {
        return dataDir().resolve("seq-%019d-%s.json".formatted(sequenceNumber, uuid));
    }

    /** 原子写入：先写 .tmp 再 ATOMIC_MOVE 替换，保证崩溃时不会出现半写文件。 */
    public void writeJsonAtomically(Path target, Object value) {
        ensureParent(target);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp." + java.util.UUID.randomUUID());
        try {
            Files.writeString(tmp, JsonCodec.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("原子写入失败: " + target, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响正确性
            }
        }
    }

    public void writeDataFile(Path target, StoredDataFile file) {
        writeJsonAtomically(target, file);
    }

    public StoredDataFile readDataFile(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return JsonCodec.fromJson(json, StoredDataFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取数据文件失败: " + path, e);
        }
    }

    public TableStateFile readState() {
        try {
            String json = Files.readString(stateFile(), StandardCharsets.UTF_8);
            return JsonCodec.fromJson(json, TableStateFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取表状态失败: " + stateFile(), e);
        }
    }

    public void writeState(TableStateFile state) {
        writeJsonAtomically(stateFile(), state);
    }

    public boolean stateExists() {
        return Files.exists(stateFile());
    }

    public <T> T readJson(Path path, Class<T> type) {
        try {
            return JsonCodec.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 JSON 失败: " + path, e);
        }
    }

    public <T> void writeJson(Path path, T value) {
        writeJsonAtomically(path, value);
    }

    private void ensureParent(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("创建目录失败: " + target.getParent(), e);
        }
    }
}
