package com.github.lisama569.lakehousetableformatjava.lakehouse.evolve;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;

/**
 * 表结构演进指令（一次提交内按顺序应用）。
 * JSON 通过逻辑标签 op ∈ {ADD, RENAME, DROP} 区分，不暴露实现类名：
 * <pre>
 *   {"op":"ADD","name":"region","type":"STRING","nullable":true,"defaultValue":"NA"}
 *   {"op":"RENAME","from":"name","to":"full_name"}
 *   {"op":"DROP","name":"score"}
 * </pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "op", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = SchemaChange.AddColumn.class, name = "ADD"),
        @JsonSubTypes.Type(value = SchemaChange.RenameColumn.class, name = "RENAME"),
        @JsonSubTypes.Type(value = SchemaChange.DropColumn.class, name = "DROP")
})
public sealed interface SchemaChange
        permits SchemaChange.AddColumn, SchemaChange.RenameColumn, SchemaChange.DropColumn {

    record AddColumn(String op, String name, DataType type, Boolean nullable, Object defaultValue)
            implements SchemaChange {
        public AddColumn(String name, DataType type, boolean nullable, Object defaultValue) {
            this("ADD", name, type, nullable, defaultValue);
        }
    }

    record RenameColumn(String op, String from, String to) implements SchemaChange {
        public RenameColumn(String from, String to) {
            this("RENAME", from, to);
        }
    }

    record DropColumn(String op, String name) implements SchemaChange {
        public DropColumn(String name) {
            this("DROP", name);
        }
    }
}
