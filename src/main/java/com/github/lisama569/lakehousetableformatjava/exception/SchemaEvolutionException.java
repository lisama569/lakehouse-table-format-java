package com.github.lisama569.lakehousetableformatjava.exception;

/** 非法的结构演进操作（列名重复、改动主键列等）。 */
public class SchemaEvolutionException extends LakehouseException {
    public SchemaEvolutionException(String message) {
        super(message);
    }
}
