package com.qingshanyuluo.baton.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "baton")
public record BatonProperties(
        List<BackendConfig> backends,
        FailoverConfig failover
) {
    public record BackendConfig(
            String name,
            String url,
            int priority
    ) {}

    public record FailoverConfig(
            Duration connectTimeout,
            Duration readTimeout,
            Duration streamingIdleTimeout,
            Duration globalTimeout,
            long maxBodySize,
            int maxConcurrentRequests,
            Duration healthCheckInterval,
            int healthCheckThreshold
    ) {
        public FailoverConfig {
            if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
            if (readTimeout == null) readTimeout = Duration.ofSeconds(30);
            if (streamingIdleTimeout == null) streamingIdleTimeout = Duration.ofMinutes(5);
            if (globalTimeout == null) globalTimeout = Duration.ofSeconds(60);
            if (maxBodySize == 0) maxBodySize = 20 * 1024 * 1024;
            if (maxConcurrentRequests == 0) maxConcurrentRequests = 200;
            if (healthCheckInterval == null) healthCheckInterval = Duration.ofSeconds(30);
            if (healthCheckThreshold == 0) healthCheckThreshold = 3;
        }
    }
}
