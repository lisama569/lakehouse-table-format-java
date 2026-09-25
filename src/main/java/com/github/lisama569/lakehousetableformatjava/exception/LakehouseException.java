package com.github.lisama569.lakehousetableformatjava.exception;

/** 湖仓相关异常基类。 */
public class LakehouseException extends RuntimeException {
    public LakehouseException(String message) {
        super(message);
    }

    public LakehouseException(String message, Throwable cause) {
        super(message, cause);
    }
}
