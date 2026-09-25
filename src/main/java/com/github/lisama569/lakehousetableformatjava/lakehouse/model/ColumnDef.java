package com.github.lisama569.lakehousetableformatjava.lakehouse.model;

/** 列定义：列 id 在表的整个生命周期内稳定且永不复用。 */
public final class ColumnDef {
    private final int id;
    private final String name;
    private final DataType type;
    private final boolean nullable;
    private final Object defaultValue;

    public ColumnDef(int id, String name, DataType type, boolean nullable, Object defaultValue) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public DataType getType() { return type; }
    public boolean isNullable() { return nullable; }
    public Object getDefaultValue() { return defaultValue; }

    /** 改名：列 id 与类型保持不变，历史数据文件中的值因此不会错列。 */
    public ColumnDef withName(String newName) {
        return new ColumnDef(id, newName, type, nullable, defaultValue);
    }

    /** 覆盖缺省值（用于新增列时规范化外部传入的缺省值）。 */
    public ColumnDef withDefaultValue(Object newDefault) {
        return new ColumnDef(id, name, type, nullable, newDefault);
    }
}
