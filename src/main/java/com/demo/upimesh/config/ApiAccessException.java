package com.demo.upimesh.config;

import org.springframework.http.HttpStatus;

/** A request was refused by the access policy; GlobalExceptionHandler turns it into the usual JSON error. */
public class ApiAccessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiAccessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
