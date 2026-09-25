package com.github.lisama569.lakehousetableformatjava.web;

import com.github.lisama569.lakehousetableformatjava.exception.ConflictException;
import com.github.lisama569.lakehousetableformatjava.exception.LakehouseException;
import com.github.lisama569.lakehousetableformatjava.exception.SchemaEvolutionException;
import com.github.lisama569.lakehousetableformatjava.exception.TableAlreadyExistsException;
import com.github.lisama569.lakehousetableformatjava.exception.TableNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把领域异常映射为可区分的 HTTP 状态与错误体。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TableNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(LakehouseException e) {
        return build(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(TableAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> exists(LakehouseException e) {
        return build(HttpStatus.CONFLICT, "TABLE_ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, "SNAPSHOT_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(SchemaEvolutionException.class)
    public ResponseEntity<Map<String, Object>> schema(SchemaEvolutionException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_SCHEMA_EVOLUTION", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("errorCode", code, "message", message));
    }
}
