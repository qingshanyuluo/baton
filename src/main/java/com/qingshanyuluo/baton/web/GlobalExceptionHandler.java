package com.qingshanyuluo.baton.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        log.error("Unhandled exception", e);
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal gateway error\"}}";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("content-type", "application/json")
                .body(body);
    }
}
