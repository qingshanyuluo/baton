package com.qingshanyuluo.baton.service;

import com.qingshanyuluo.baton.config.BatonProperties;
import com.qingshanyuluo.baton.config.BatonProperties.BackendConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HealthTracker {

    private static final Logger log = LoggerFactory.getLogger(HealthTracker.class);

    private final BatonProperties properties;
    private final Map<String, BackendState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "health-check")
    );
    private final HttpClient httpClient;

    public HealthTracker(BatonProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.failover().connectTimeout())
                .build();
    }

    @PostConstruct
    public void init() {
        if (properties.backends() == null || properties.backends().isEmpty()) {
            throw new IllegalStateException("No backends configured");
        }
        for (BackendConfig backend : properties.backends()) {
            states.put(backend.name(), new BackendState(backend));
        }
        Duration interval = properties.failover().healthCheckInterval();
        scheduler.scheduleAtFixedRate(this::runHealthChecks,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public List<BackendConfig> getHealthyBackends() {
        return states.values().stream()
                .filter(s -> s.healthy.get() && !s.manuallyDisabled.get())
                .map(s -> s.config)
                .sorted(Comparator.comparingInt(BackendConfig::priority))
                .toList();
    }

    public List<BackendConfig> getAllBackendsByPriority() {
        return states.values().stream()
                .filter(s -> !s.manuallyDisabled.get())
                .map(s -> s.config)
                .sorted(Comparator.comparingInt(BackendConfig::priority))
                .toList();
    }

    public boolean isHealthy(String name) {
        BackendState state = states.get(name);
        return state != null && state.healthy.get() && !state.manuallyDisabled.get();
    }

    public void markUnhealthy(String name) {
        BackendState state = states.get(name);
        if (state != null) {
            state.healthy.set(false);
            state.consecutiveSuccesses.set(0);
            log.warn("Backend '{}' marked unhealthy", name);
        }
    }

    public void markDisabled(String name) {
        BackendState state = states.get(name);
        if (state != null) {
            state.manuallyDisabled.set(true);
            log.info("Backend '{}' manually disabled", name);
        }
    }

    public void markEnabled(String name) {
        BackendState state = states.get(name);
        if (state != null) {
            state.manuallyDisabled.set(false);
            state.healthy.set(true);
            state.consecutiveSuccesses.set(0);
            log.info("Backend '{}' manually enabled", name);
        }
    }

    public Map<String, BackendStatus> getStatuses() {
        Map<String, BackendStatus> result = new ConcurrentHashMap<>();
        states.forEach((name, state) -> result.put(name, new BackendStatus(
                name, state.config.url(), state.config.priority(),
                state.healthy.get(), state.manuallyDisabled.get()
        )));
        return result;
    }

    private void runHealthChecks() {
        for (BackendState state : states.values()) {
            if (state.manuallyDisabled.get() || state.healthy.get()) continue;
            try {
                HealthResult result = checkHealth(state.config);
                if (result.passed()) {
                    int successes = state.consecutiveSuccesses.incrementAndGet();
                    int threshold = result.tcpFallback() ? 5 : properties.failover().healthCheckThreshold();
                    if (successes >= threshold) {
                        state.healthy.set(true);
                        log.info("Backend '{}' recovered (tcpFallback={}, successes={}/{})",
                                state.config.name(), result.tcpFallback(), successes, threshold);
                    }
                } else {
                    state.consecutiveSuccesses.set(0);
                }
            } catch (Exception e) {
                state.consecutiveSuccesses.set(0);
            }
        }
    }

    private HealthResult checkHealth(BackendConfig backend) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(backend.url() + "/v1/models"))
                    .timeout(properties.failover().connectTimeout())
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) return new HealthResult(true, false);
            if (response.statusCode() == 404) return new HealthResult(checkTcp(backend), true);
            return new HealthResult(false, false);
        } catch (Exception e) {
            return new HealthResult(checkTcp(backend), true);
        }
    }

    private record HealthResult(boolean passed, boolean tcpFallback) {}

    private boolean checkTcp(BackendConfig backend) {
        try {
            URI uri = URI.create(backend.url());
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port),
                        (int) properties.failover().connectTimeout().toMillis());
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static class BackendState {
        final BackendConfig config;
        final AtomicBoolean healthy = new AtomicBoolean(true);
        final AtomicBoolean manuallyDisabled = new AtomicBoolean(false);
        final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);

        BackendState(BackendConfig config) {
            this.config = config;
        }
    }

    public record BackendStatus(String name, String url, int priority, boolean healthy, boolean disabled) {}
}
