package com.github.lisama569.lakehousetableformatjava.lakehouse.model;

import com.github.lisama569.lakehousetableformatjava.lakehouse.evolve.SchemaChange;
import java.util.Collection;
import java.util.List;

/** 某一个 schema 版本的完整定义，列通过稳定 id 与数据文件中的值对应。 */
public final class TableSchema {
    private final long schemaId;
    private final int nextColumnId;
    private final List<ColumnDef> columns;
    private final List<Integer> keyColumnIds;

    public TableSchema(long schemaId, int nextColumnId, List<ColumnDef> columns, List<Integer> keyColumnIds) {
        this.schemaId = schemaId;
        this.nextColumnId = nextColumnId;
        this.columns = List.copyOf(columns);
        this.keyColumnIds = List.copyOf(keyColumnIds);
    }

    public long getSchemaId() { return schemaId; }
    public int getNextColumnId() { return nextColumnId; }
    public List<ColumnDef> getColumns() { return columns; }
    public List<Integer> getKeyColumnIds() { return keyColumnIds; }

    public ColumnDef findByName(String name) {
        for (ColumnDef c : columns) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    public ColumnDef findById(int id) {
        for (ColumnDef c : columns) {
            if (c.getId() == id) {
                return c;
            }
        }
        return null;
    }

    /**
     * 按顺序应用一组结构变更，产生 schemaId+1 的新版本。
     * 规则：
     * <ul>
     *   <li>ADD：列名必须当前不存在；列 id 取本版本 nextColumnId 且永不复用；
     *       历史行在该列上取缺省值，故非空列必须显式提供非空且类型匹配的缺省值。</li>
     *   <li>RENAME：仅改列名，列 id 不变，因此历史数据文件按 id 读取，不会错列。</li>
     *   <li>DROP：主键列禁止删除；被删列的 id 永不复用，旧数据文件中的该列值被忽略而非错配。</li>
     * </ul>
     */
    public TableSchema apply(Collection<SchemaChange> changes) {
        List<ColumnDef> current = new java.util.ArrayList<>(columns);
        int nextId = nextColumnId;
        java.util.Set<Integer> keyIds = new java.util.HashSet<>(keyColumnIds);

        for (SchemaChange change : changes) {
            switch (change) {
                case SchemaChange.AddColumn add -> {
                    if (add.name() == null || add.name().isBlank()) {
                        throw new IllegalArgumentException("ADD COLUMN：列名不能为空");
                    }
                    boolean exists = current.stream().anyMatch(c -> c.getName().equals(add.name()));
                    if (exists) {
                        throw new IllegalArgumentException("ADD COLUMN：列名已存在 -> " + add.name());
                    }
                    if (add.type() == null) {
                        throw new IllegalArgumentException("ADD COLUMN：列类型不能为空 -> " + add.name());
                    }
                    Object normalizedDefault;
                    try {
                        normalizedDefault = add.type().normalize(add.defaultValue());
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(
                                "ADD COLUMN：缺省值类型不匹配 -> " + add.name() + "（" + ex.getMessage() + "）");
                    }
                    if (!add.nullable() && normalizedDefault == null) {
                        throw new IllegalArgumentException(
                                "ADD COLUMN：新增非空列必须提供非空缺省值 -> " + add.name()
                                        + "（否则历史行无法确定该列取值）");
                    }
                    current.add(new ColumnDef(nextId, add.name(), add.type(), add.nullable(), normalizedDefault));
                    nextId++;
                }
                case SchemaChange.RenameColumn rename -> {
                    if (rename.from() == null || rename.to() == null
                            || rename.from().isBlank() || rename.to().isBlank()) {
                        throw new IllegalArgumentException("RENAME COLUMN：原列名与新列名均不能为空");
                    }
                    int idx = -1;
                    for (int i = 0; i < current.size(); i++) {
                        if (current.get(i).getName().equals(rename.from())) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx < 0) {
                        throw new IllegalArgumentException("RENAME COLUMN：原列不存在 -> " + rename.from());
                    }
                    boolean targetExists = current.stream().anyMatch(c -> c.getName().equals(rename.to()));
                    if (targetExists) {
                        throw new IllegalArgumentException("RENAME COLUMN：新列名已存在 -> " + rename.to());
                    }
                    current.set(idx, current.get(idx).withName(rename.to()));
                }
                case SchemaChange.DropColumn drop -> {
                    if (drop.name() == null || drop.name().isBlank()) {
                        throw new IllegalArgumentException("DROP COLUMN：列名不能为空");
                    }
                    ColumnDef victim = null;
                    for (ColumnDef c : current) {
                        if (c.getName().equals(drop.name())) {
                            victim = c;
                            break;
                        }
                    }
                    if (victim == null) {
                        throw new IllegalArgumentException("DROP COLUMN：列不存在 -> " + drop.name());
                    }
                    if (keyIds.contains(victim.getId())) {
                        throw new IllegalArgumentException(
                                "DROP COLUMN：主键列禁止删除 -> " + drop.name());
                    }
                    current.remove(victim);
                }
            }
        }
        return new TableSchema(schemaId + 1, nextId, current, List.copyOf(keyIds));
    }
}
