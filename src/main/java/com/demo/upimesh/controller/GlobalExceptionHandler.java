package com.demo.upimesh.controller;

import com.demo.upimesh.config.ApiAccessException;
import com.demo.upimesh.dto.ApiError;
import com.demo.upimesh.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

/** One consistent JSON error shape for every API failure. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body or parameter could not be read", List.of());
    }

    @ExceptionHandler(ApiAccessException.class)
    ResponseEntity<ApiError> denied(ApiAccessException e) {
        ResponseEntity<ApiError> r = error(e.status(), e.code(), e.getMessage(), List.of());
        return e.status() == HttpStatus.UNAUTHORIZED ? ResponseEntity.status(r.getStatusCode()).header("WWW-Authenticate", "Bearer").body(r.getBody()) : r;
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT", e.getMessage(), List.of());
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError> unexpected(RuntimeException e) {
        log.error("Unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", List.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message, List<String> details) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message, details));
    }
}
