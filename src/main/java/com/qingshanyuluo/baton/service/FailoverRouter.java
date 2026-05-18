package com.qingshanyuluo.baton.service;

import com.qingshanyuluo.baton.config.BatonProperties;
import com.qingshanyuluo.baton.config.BatonProperties.BackendConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FailoverRouter {

    private static final Logger log = LoggerFactory.getLogger(FailoverRouter.class);
    private static final Set<Integer> SOFT_FAILURE_CODES = Set.of(429);
    private static final Set<Integer> NO_FAILOVER_CODES = Set.of(400, 401, 403, 404, 422);
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding",
            "te", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate"
    );
    private static final String SSE_ERROR = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Backend connection lost during streaming\"}}\n\n";

    private final WebClient webClient;
    private final HealthTracker healthTracker;
    private final BatonProperties properties;
    private final Semaphore concurrencyLimiter;
    private final AtomicInteger activeStreaming = new AtomicInteger(0);
    private final SkipRuleEvaluator skipRuleEvaluator;

    public FailoverRouter(WebClient webClient, HealthTracker healthTracker, BatonProperties properties,
                          SkipRuleEvaluator skipRuleEvaluator) {
        this.webClient = webClient;
        this.healthTracker = healthTracker;
        this.properties = properties;
        this.skipRuleEvaluator = skipRuleEvaluator;
        this.concurrencyLimiter = new Semaphore(properties.failover().maxConcurrentRequests());
    }

    public RouteResult route(String path, HttpHeaders requestHeaders, byte[] body, boolean streaming) {
        if (!concurrencyLimiter.tryAcquire()) {
            return RouteResult.error(HttpStatus.SERVICE_UNAVAILABLE,
                    "overloaded_error", "Too many concurrent requests");
        }
        if (streaming && activeStreaming.incrementAndGet() > properties.failover().maxConcurrentRequests()) {
            activeStreaming.decrementAndGet();
            concurrencyLimiter.release();
            return RouteResult.error(HttpStatus.SERVICE_UNAVAILABLE,
                    "overloaded_error", "Too many concurrent streaming requests");
        }
        try {
            RouteResult result = doRoute(path, requestHeaders, body, streaming);
            if (result.isStreaming() && result.streamingBody() != null) {
                StreamingResponseBody original = result.streamingBody();
                StreamingResponseBody wrapped = outputStream -> {
                    try {
                        original.writeTo(outputStream);
                    } finally {
                        activeStreaming.decrementAndGet();
                        concurrencyLimiter.release();
                    }
                };
                return new RouteResult(result.status(), result.headers(), null, wrapped,
                        result.success(), result.noFailover(), result.softFailover(), result.errorMessage());
            }
            return result;
        } catch (Exception e) {
            if (streaming) activeStreaming.decrementAndGet();
            throw e;
        } finally {
            if (!streaming) concurrencyLimiter.release();
        }
    }

    private RouteResult doRoute(String path, HttpHeaders requestHeaders, byte[] body, boolean streaming) {
        List<BackendConfig> backends = healthTracker.getHealthyBackends();
        if (backends.isEmpty()) {
            backends = healthTracker.getAllBackendsByPriority();
        }
        if (backends.isEmpty()) {
            return RouteResult.error(HttpStatus.BAD_GATEWAY,
                    "overloaded_error", "All backends unavailable");
        }

        Instant deadline = Instant.now().plus(properties.failover().globalTimeout());
        List<BackendConfig> lenientSkipped = new java.util.ArrayList<>();
        RouteResult lastError = null;
        boolean allStrictSkipped = true;

        // Round 1: try non-skip backends, collect lenient-skipped
        for (BackendConfig backend : backends) {
            if (Instant.now().isAfter(deadline)) {
                return RouteResult.error(HttpStatus.GATEWAY_TIMEOUT,
                        "timeout_error", "Global timeout exceeded");
            }
            SkipRuleEvaluator.SkipResult skip = skipRuleEvaluator.evaluate(backend, requestHeaders, body);
            if (skip.strict()) continue;
            if (skip.lenient()) {
                lenientSkipped.add(backend);
                continue;
            }
            allStrictSkipped = false;
            RouteResult result = streaming
                    ? tryBackendStreaming(backend, path, requestHeaders, body)
                    : tryBackend(backend, path, requestHeaders, body);
            if (result.success()) return result;
            if (result.noFailover()) return result;
            lastError = result;
        }

        // Round 2: fallback to lenient-skipped backends
        for (BackendConfig backend : lenientSkipped) {
            if (Instant.now().isAfter(deadline)) {
                return RouteResult.error(HttpStatus.GATEWAY_TIMEOUT,
                        "timeout_error", "Global timeout exceeded");
            }
            RouteResult result = streaming
                    ? tryBackendStreaming(backend, path, requestHeaders, body)
                    : tryBackend(backend, path, requestHeaders, body);
            if (result.success()) return result;
            if (result.noFailover()) return result;
            lastError = result;
        }

        if (allStrictSkipped && lastError == null) {
            return RouteResult.error(HttpStatus.BAD_GATEWAY,
                    "invalid_request_error", "No compatible backend found for this request");
        }
        if (lastError != null && lastError.softFailover()) return lastError;
        return lastError != null ? lastError :
                RouteResult.error(HttpStatus.BAD_GATEWAY, "overloaded_error", "All backends unavailable");
    }

    private RouteResult tryBackend(BackendConfig backend, String path,
                                   HttpHeaders requestHeaders, byte[] body) {
        String url = backend.url() + path;
        HttpHeaders filtered = filterHeaders(requestHeaders);
        Duration readTimeout = properties.failover().readTimeout();
        try {
            var response = webClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(filtered))
                    .bodyValue(body)
                    .retrieve()
                    .toEntity(byte[].class)
                    .timeout(readTimeout)
                    .block();

            if (response == null) {
                healthTracker.markUnhealthy(backend.name());
                return RouteResult.failover("Null response");
            }
            return RouteResult.success(
                    HttpStatus.valueOf(response.getStatusCode().value()),
                    response.getHeaders(),
                    response.getBody());
        } catch (WebClientResponseException e) {
            return handleResponseError(e, backend);
        } catch (WebClientRequestException e) {
            healthTracker.markUnhealthy(backend.name());
            return RouteResult.failover("Connection failed");
        } catch (Exception e) {
            healthTracker.markUnhealthy(backend.name());
            return RouteResult.failover(e.getMessage());
        }
    }

    private RouteResult tryBackendStreaming(BackendConfig backend, String path,
                                            HttpHeaders requestHeaders, byte[] body) {
        String url = backend.url() + path;
        HttpHeaders filtered = filterHeaders(requestHeaders);
        Duration readTimeout = properties.failover().readTimeout();
        Duration streamingIdleTimeout = properties.failover().streamingIdleTimeout();
        try {
            var responseEntity = webClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(filtered))
                    .bodyValue(body)
                    .retrieve()
                    .toEntityFlux(byte[].class)
                    .timeout(readTimeout)
                    .block();

            if (responseEntity == null) {
                healthTracker.markUnhealthy(backend.name());
                return RouteResult.failover("Null response");
            }

            HttpStatus status = HttpStatus.valueOf(responseEntity.getStatusCode().value());
            if (status.is2xxSuccessful()) {
                Flux<byte[]> bodyFlux = responseEntity.getBody() != null ? responseEntity.getBody() : Flux.empty();

                StreamingResponseBody streamingBody = outputStream -> {
                    bodyFlux.timeout(streamingIdleTimeout)
                            .doOnNext(chunk -> {
                                try {
                                    outputStream.write(chunk);
                                    outputStream.flush();
                                } catch (Exception ex) {
                                    throw new RuntimeException("OutputStream write failed", ex);
                                }
                            })
                            .doOnError(err -> writeSseError(outputStream))
                            .doOnComplete(() -> {})
                            .blockLast();
                };

                return RouteResult.streaming(status,
                        responseEntity.getHeaders(), streamingBody);
            } else {
                byte[] errorBody = responseEntity.getBody() != null
                        ? responseEntity.getBody()
                                .timeout(Duration.ofSeconds(5))
                                .collectList()
                                .block(Duration.ofSeconds(5))
                                .stream()
                        .reduce(new byte[0], (a, b) -> {
                            byte[] c = new byte[a.length + b.length];
                            System.arraycopy(a, 0, c, 0, a.length);
                            System.arraycopy(b, 0, c, a.length, b.length);
                            return c;
                        })
                        : new byte[0];
                return handleStreamingResponseError(status.value(),
                        responseEntity.getHeaders(), errorBody, backend);
            }
        } catch (WebClientResponseException e) {
            return handleResponseError(e, backend);
        } catch (WebClientRequestException e) {
            healthTracker.markUnhealthy(backend.name());
            return RouteResult.failover("Connection failed");
        } catch (Exception e) {
            healthTracker.markUnhealthy(backend.name());
            return RouteResult.failover(e.getMessage());
        }
    }

    private RouteResult handleResponseError(WebClientResponseException e, BackendConfig backend) {
        int status = e.getStatusCode().value();
        if (NO_FAILOVER_CODES.contains(status)) {
            return RouteResult.noFailover(
                    HttpStatus.valueOf(status), e.getHeaders(), e.getResponseBodyAsByteArray());
        }
        if (SOFT_FAILURE_CODES.contains(status)) {
            return RouteResult.softFailover(
                    HttpStatus.valueOf(status), e.getHeaders(), e.getResponseBodyAsByteArray());
        }
        healthTracker.markUnhealthy(backend.name());
        return RouteResult.failover("Backend returned " + status);
    }

    private RouteResult handleStreamingResponseError(int status, HttpHeaders headers,
                                                     byte[] body, BackendConfig backend) {
        if (NO_FAILOVER_CODES.contains(status)) {
            return RouteResult.noFailover(HttpStatus.valueOf(status), headers, body);
        }
        if (SOFT_FAILURE_CODES.contains(status)) {
            return RouteResult.softFailover(HttpStatus.valueOf(status), headers, body);
        }
        healthTracker.markUnhealthy(backend.name());
        return RouteResult.failover("Backend returned " + status);
    }

    private void writeSseError(java.io.OutputStream outputStream) {
        try {
            outputStream.write(SSE_ERROR.getBytes());
            outputStream.flush();
        } catch (Exception ignored) {
        }
    }

    private HttpHeaders filterHeaders(HttpHeaders original) {
        HttpHeaders filtered = new HttpHeaders();
        original.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase()) && !name.equalsIgnoreCase("host")) {
                filtered.addAll(name, values);
            }
        });
        return filtered;
    }
}
