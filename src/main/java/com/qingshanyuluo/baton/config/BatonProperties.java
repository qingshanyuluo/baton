package com.qingshanyuluo.baton.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ConfigurationProperties(prefix = "baton")
public record BatonProperties(
        List<BackendConfig> backends,
        FailoverConfig failover
) {
    private static final Logger log = LoggerFactory.getLogger(BatonProperties.class);

    @PostConstruct
    public void validate() {
        if (backends != null) {
            for (BackendConfig backend : backends) {
                if (backend.skipRules() != null) {
                    for (SkipRule rule : backend.skipRules()) {
                        if ("model-pattern".equals(rule.type()) && rule.pattern() != null) {
                            try {
                                Pattern.compile(rule.pattern());
                            } catch (PatternSyntaxException e) {
                                throw new IllegalStateException(
                                        "Invalid regex in skip-rule for backend '" + backend.name() + "': " + rule.pattern(), e);
                            }
                        }
                    }
                }
            }
        }
    }

    public record BackendConfig(
            String name,
            String url,
            int priority,
            List<SkipRule> skipRules
    ) {
        public BackendConfig {
            if (skipRules == null) skipRules = List.of();
        }
    }

    public record SkipRule(
            String type,
            String pattern,
            List<String> values,
            String field,
            String header,
            String mode,
            String description
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
