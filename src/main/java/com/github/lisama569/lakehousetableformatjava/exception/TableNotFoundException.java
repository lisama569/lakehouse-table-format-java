package com.github.lisama569.lakehousetableformatjava.exception;

public class TableNotFoundException extends LakehouseException {
    public TableNotFoundException(String table) {
        super("table not found: " + table);
    }
}
