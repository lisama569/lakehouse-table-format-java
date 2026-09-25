package com.github.lisama569.lakehousetableformatjava.lakehouse.api.web;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** 把领域异常映射为可解释的 HTTP 响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CommitRejectedException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleCommitRejected(CommitRejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiDtos.ErrorResponse("commit_rejected", ex.getReason().name(), ex.getMessage(),
                        ex.getRowRejections()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiDtos.ErrorResponse("invalid_request", "INVALID_REQUEST", ex.getMessage(), List.of()));
    }
}
