package com.github.lisama569.lakehousetableformatjava.exception;

/** 非法的结构演进操作（列名重复、删除主键列、非法列名等）。 */
public class SchemaEvolutionException extends LakehouseException {
    public SchemaEvolutionException(String message) {
        super(message);
    }
}
