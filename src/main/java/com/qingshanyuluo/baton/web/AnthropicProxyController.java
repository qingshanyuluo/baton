package com.qingshanyuluo.baton.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingshanyuluo.baton.config.BatonProperties;
import com.qingshanyuluo.baton.service.FailoverRouter;
import com.qingshanyuluo.baton.service.RouteResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;

@RestController
public class AnthropicProxyController {

    private final FailoverRouter router;
    private final BatonProperties properties;
    private final ObjectMapper objectMapper;

    public AnthropicProxyController(FailoverRouter router, BatonProperties properties, ObjectMapper objectMapper) {
        this.router = router;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/v1/messages")
    public ResponseEntity<?> proxyMessages(@RequestBody byte[] body,
                                            @RequestHeader HttpHeaders headers) throws IOException {
        if (body.length > properties.failover().maxBodySize()) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .header("content-type", "application/json")
                    .body(errorBody("invalid_request_error", "Request body too large"));
        }

        boolean streaming = isStreamingRequest(body);
        RouteResult result = router.route("/v1/messages", headers, body, streaming);

        HttpHeaders responseHeaders = buildResponseHeaders(result);

        if (result.isStreaming()) {
            return ResponseEntity.status(result.status())
                    .headers(responseHeaders)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(result.streamingBody());
        }

        return ResponseEntity.status(result.status())
                .headers(responseHeaders)
                .body(result.body());
    }

    private boolean isStreamingRequest(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode streamNode = root.get("stream");
            return streamNode != null && streamNode.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private HttpHeaders buildResponseHeaders(RouteResult result) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (result.headers() != null) {
            result.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("transfer-encoding")) {
                    responseHeaders.addAll(name, values);
                }
            });
        }
        return responseHeaders;
    }

    private byte[] errorBody(String type, String message) {
        return ("{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}").getBytes();
    }
}
