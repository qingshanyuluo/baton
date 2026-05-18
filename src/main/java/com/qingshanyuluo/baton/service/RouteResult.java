package com.qingshanyuluo.baton.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public record RouteResult(
        HttpStatus status,
        HttpHeaders headers,
        byte[] body,
        StreamingResponseBody streamingBody,
        boolean success,
        boolean noFailover,
        boolean softFailover,
        String errorMessage
) {
    public static RouteResult success(HttpStatus status, HttpHeaders headers, byte[] body) {
        return new RouteResult(status, headers, body, null, true, false, false, null);
    }

    public static RouteResult streaming(HttpStatus status, HttpHeaders headers, StreamingResponseBody streamingBody) {
        return new RouteResult(status, headers, null, streamingBody, true, false, false, null);
    }

    public static RouteResult error(HttpStatus status, String errorType, String message) {
        String errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"" + errorType + "\",\"message\":\"" + message + "\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("content-type", "application/json");
        return new RouteResult(status, headers, errorBody.getBytes(), null, false, false, false, message);
    }

    public static RouteResult noFailover(HttpStatus status, HttpHeaders headers, byte[] body) {
        return new RouteResult(status, headers, body, null, false, true, false, null);
    }

    public static RouteResult softFailover(HttpStatus status, HttpHeaders headers, byte[] body) {
        return new RouteResult(status, headers, body, null, false, false, true, null);
    }

    public static RouteResult failover(String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("content-type", "application/json");
        String errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"All backends unavailable\"}}";
        return new RouteResult(HttpStatus.BAD_GATEWAY, headers, errorBody.getBytes(), null, false, false, false, message);
    }

    public boolean isStreaming() {
        return streamingBody != null;
    }
}
