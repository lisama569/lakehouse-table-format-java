package com.github.lisama569.lakehousetableformatjava.lakehouse.table;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.RejectReason;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.ColumnDef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 本地文件系统目录实现：warehouseRoot/tableName/{metadata,data}。 */
public final class LocalLakehouseCatalog implements LakehouseCatalog {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,128}");

    private final Path warehouseRoot;
    private final ConcurrentHashMap<String, LocalTable> cache = new ConcurrentHashMap<>();

    public LocalLakehouseCatalog(Path warehouseRoot) {
        this.warehouseRoot = warehouseRoot;
        try {
            Files.createDirectories(warehouseRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("初始化仓库目录失败: " + warehouseRoot, e);
        }
    }

    @Override
    public Table createTable(String tableName, List<ColumnSpec> columns, List<String> primaryKeyColumns) {
        validateName(tableName);
        if (columns == null || columns.isEmpty()) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST, "建表至少需要一列");
        }
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                    "建表必须声明主键列，主键用于追加冲突判定与合并确定性");
        }
        Path tableDir = warehouseRoot.resolve(tableName);
        if (Files.exists(tableDir.resolve("metadata").resolve("table-state.json"))) {
            throw new CommitRejectedException(RejectReason.TABLE_ALREADY_EXISTS, "表已存在: " + tableName);
        }

        List<ColumnDef> defs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ColumnSpec spec : columns) {
            if (spec.name() == null || spec.name().isBlank()) {
                throw new CommitRejectedException(RejectReason.INVALID_REQUEST, "列名不能为空");
            }
            if (spec.type() == null) {
                throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                        "列类型不能为空: " + spec.name());
            }
            if (!names.add(spec.name())) {
                throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                        "列名重复: " + spec.name());
            }
            boolean nullable = spec.nullable() == null || spec.nullable();
            Object normalizedDefault;
            try {
                normalizedDefault = spec.type().normalize(spec.defaultValue());
            } catch (IllegalArgumentException ex) {
                throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                        "列 %s 缺省值类型不匹配: %s".formatted(spec.name(), ex.getMessage()));
            }
            if (!nullable && normalizedDefault == null) {
                // 建表允许非空列无缺省值（写入时必须显式提供）；
                // 只有后续 ADD COLUMN 新增非空列才强制要求缺省值，以便为历史行定值。
            }
            defs.add(new ColumnDef(names.size() - 1, spec.name(), spec.type(), nullable, normalizedDefault));
        }

        List<Integer> keyIds = new ArrayList<>();
        Set<String> pkSet = new HashSet<>(primaryKeyColumns);
        if (pkSet.size() != primaryKeyColumns.size()) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST, "主键列存在重复");
        }
        for (String pk : primaryKeyColumns) {
            ColumnDef col = null;
            for (ColumnDef c : defs) {
                if (c.getName().equals(pk)) {
                    col = c;
                    break;
                }
            }
            if (col == null) {
                throw new CommitRejectedException(RejectReason.INVALID_REQUEST, "主键列不存在: " + pk);
            }
            keyIds.add(col.getId());
        }
        // 主键列强制非空
        List<ColumnDef> fixed = new ArrayList<>();
        for (ColumnDef c : defs) {
            if (keyIds.contains(c.getId()) && c.isNullable()) {
                fixed.add(new ColumnDef(c.getId(), c.getName(), c.getType(), false, c.getDefaultValue()));
            } else {
                fixed.add(c);
            }
        }

        LocalTable table = LocalTable.create(tableDir, tableName, fixed, keyIds);
        cache.put(tableName, table);
        return table;
    }

    @Override
    public Table loadTable(String tableName) {
        validateName(tableName);
        LocalTable cached = cache.get(tableName);
        if (cached != null) {
            return cached;
        }
        Path tableDir = warehouseRoot.resolve(tableName);
        if (!Files.exists(tableDir.resolve("metadata").resolve("table-state.json"))) {
            throw new CommitRejectedException(RejectReason.TABLE_NOT_FOUND, "表不存在: " + tableName);
        }
        return cache.computeIfAbsent(tableName, k -> LocalTable.load(tableDir));
    }

    @Override
    public boolean tableExists(String tableName) {
        validateName(tableName);
        return Files.exists(warehouseRoot.resolve(tableName).resolve("metadata").resolve("table-state.json"));
    }

    @Override
    public void dropTable(String tableName) {
        validateName(tableName);
        cache.remove(tableName);
        Path tableDir = warehouseRoot.resolve(tableName);
        if (!Files.exists(tableDir)) {
            throw new CommitRejectedException(RejectReason.TABLE_NOT_FOUND, "表不存在: " + tableName);
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
            throw new UncheckedIOException("删除表目录失败: " + tableDir, e);
        }
    }

    private static void validateName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new CommitRejectedException(RejectReason.INVALID_REQUEST,
                    "表名非法（只允许 1-128 位字母、数字、下划线、中划线）: " + name);
        }
    }
}
