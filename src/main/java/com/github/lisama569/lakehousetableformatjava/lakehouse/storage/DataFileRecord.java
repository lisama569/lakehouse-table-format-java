package com.github.lisama569.lakehousetableformatjava.lakehouse.storage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 数据文件中的一条记录：按列 id 存值。
 * 列名改名不影响该文件；新增列在文件中缺席并由读取投影补缺省值；删除列只是读时忽略。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class DataFileRecord {
    private String key;
    private Map<Integer, Object> values;

    public DataFileRecord() {
    }

    public DataFileRecord(String key, Map<Integer, Object> values) {
        this.key = key;
        this.values = values;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Map<Integer, Object> getValues() { return values; }
    public void setValues(Map<Integer, Object> values) { this.values = values; }
}
