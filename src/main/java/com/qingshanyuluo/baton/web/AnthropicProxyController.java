package com.qingshanyuluo.baton.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingshanyuluo.baton.config.BatonProperties;
import com.qingshanyuluo.baton.service.FailoverRouter;
import com.qingshanyuluo.baton.service.RouteResult;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

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

    @PostMapping("/v1/**")
    public void proxyRequest(jakarta.servlet.http.HttpServletRequest request,
                              jakarta.servlet.http.HttpServletResponse response,
                              @RequestBody byte[] body,
                              @RequestHeader HttpHeaders headers) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (body.length > properties.failover().maxBodySize()) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getOutputStream().write(errorBody("invalid_request_error", "Request body too large"));
            return;
        }

        boolean streaming = isStreamingRequest(body);
        RouteResult result = router.route(path, headers, body, streaming);

        response.setStatus(result.status().value());
        if (result.headers() != null) {
            result.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("transfer-encoding")) {
                    values.forEach(v -> response.addHeader(name, v));
                }
            });
        }

        if (result.isStreaming() && result.streamingBody() != null) {
            response.setContentType("text/event-stream");
            result.streamingBody().writeTo(response.getOutputStream());
            response.flushBuffer();
        } else if (result.body() != null) {
            response.getOutputStream().write(result.body());
        }
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
