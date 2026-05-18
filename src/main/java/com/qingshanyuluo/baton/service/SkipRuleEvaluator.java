package com.qingshanyuluo.baton.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingshanyuluo.baton.config.BatonProperties.BackendConfig;
import com.qingshanyuluo.baton.config.BatonProperties.SkipRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class SkipRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SkipRuleEvaluator.class);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public SkipRuleEvaluator(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public SkipResult evaluate(BackendConfig backend, HttpHeaders headers, byte[] body) {
        List<SkipRule> rules = backend.skipRules();
        if (rules == null || rules.isEmpty()) {
            return SkipResult.NO_MATCH;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            recordMetric(backend.name(), "none", "none", "parse_error");
            return SkipResult.NO_MATCH;
        }

        boolean anyStrict = false;
        boolean anyLenient = false;
        String decisionRule = null;

        for (SkipRule rule : rules) {
            String mode = rule.mode() != null ? rule.mode() : "strict";
            boolean matched = switch (rule.type()) {
                case "model-pattern" -> matchModelPattern(rule, root);
                case "content-type" -> matchContentType(rule, root);
                case "has-field" -> matchHasField(rule, root);
                case "header-present" -> matchHeaderPresent(rule, headers);
                default -> false;
            };

            if (matched) {
                String matchDetail = buildMatchDetail(rule, root, headers);
                recordMetric(backend.name(), rule.type(), mode, mode.equals("strict") ? "strict_skip" : "lenient_defer");
                log.info("Skipping backend '{}': rule={}, mode={}, matched={}, detail={}",
                        backend.name(), rule.type(), mode, matchDetail,
                        rule.description() != null ? rule.description() : "");
                if (decisionRule == null) {
                    decisionRule = rule.type();
                }
                if ("strict".equals(mode)) {
                    anyStrict = true;
                } else {
                    anyLenient = true;
                }
            }
        }

        if (anyStrict) return new SkipResult(true, false, decisionRule);
        if (anyLenient) return new SkipResult(false, true, decisionRule);
        recordMetric(backend.name(), "none", "none", "no_match");
        return SkipResult.NO_MATCH;
    }

    private String buildMatchDetail(SkipRule rule, JsonNode root, HttpHeaders headers) {
        return switch (rule.type()) {
            case "model-pattern" -> "pattern='" + rule.pattern() + "' against model='" + root.get("model").asText("") + "'";
            case "content-type" -> "values=" + rule.values();
            case "has-field" -> "field='" + rule.field() + "'";
            case "header-present" -> "header='" + rule.header() + "'";
            default -> "";
        };
    }

    private boolean matchModelPattern(SkipRule rule, JsonNode root) {
        JsonNode modelNode = root.get("model");
        if (modelNode == null || !modelNode.isTextual()) return false;
        String model = modelNode.asText();
        Pattern pattern = patternCache.computeIfAbsent(rule.pattern(), Pattern::compile);
        return pattern.matcher(model).find();
    }

    private boolean matchContentType(SkipRule rule, JsonNode root) {
        if (rule.values() == null) return false;
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray()) return false;

        for (JsonNode message : messages) {
            JsonNode content = message.get("content");
            if (content == null) continue;
            if (content.isTextual()) continue;

            if (content.isArray()) {
                for (JsonNode block : content) {
                    if (block.has("type") && rule.values().contains(block.get("type").asText())) {
                        return true;
                    }
                }
            }
        }

        JsonNode system = root.get("system");
        if (system != null && system.isArray()) {
            for (JsonNode block : system) {
                if (block.has("type") && rule.values().contains(block.get("type").asText())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean matchHasField(SkipRule rule, JsonNode root) {
        return hasFieldRecursive(root, rule.field());
    }

    private boolean hasFieldRecursive(JsonNode node, String fieldName) {
        if (node == null) return false;
        if (node.isObject()) {
            if (node.has(fieldName)) return true;
            var iter = node.fields();
            while (iter.hasNext()) {
                if (hasFieldRecursive(iter.next().getValue(), fieldName)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasFieldRecursive(child, fieldName)) return true;
            }
        }
        return false;
    }

    private boolean matchHeaderPresent(SkipRule rule, HttpHeaders headers) {
        return headers.containsKey(rule.header());
    }

    private void recordMetric(String backend, String rule, String mode, String action) {
        try {
            Counter.builder("baton.skip.evaluate")
                    .tag("backend", backend)
                    .tag("rule", rule)
                    .tag("mode", mode)
                    .tag("action", action)
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("Failed to record skip metric: backend={}, rule={}, mode={}, action={}",
                    backend, rule, mode, action, e);
        }
    }

    public record SkipResult(boolean strict, boolean lenient, String rule) {
        public static final SkipResult NO_MATCH = new SkipResult(false, false, null);
    }
}
