package com.github.lisama569.lakehousetableformatjava.exception;

public class TableAlreadyExistsException extends LakehouseException {
    public TableAlreadyExistsException(String table) {
        super("table already exists: " + table);
    }
}
