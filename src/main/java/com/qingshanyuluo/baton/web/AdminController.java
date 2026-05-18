package com.qingshanyuluo.baton.web;

import com.qingshanyuluo.baton.service.HealthTracker;
import com.qingshanyuluo.baton.service.HealthTracker.BackendStatus;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Endpoint(id = "backends")
public class AdminController {

    private final HealthTracker healthTracker;

    public AdminController(HealthTracker healthTracker) {
        this.healthTracker = healthTracker;
    }

    @ReadOperation
    public Map<String, BackendStatus> listBackends() {
        return healthTracker.getStatuses();
    }

    @WriteOperation
    public BackendActionResult manageBackend(@Selector String name, @Nullable String action) {
        return switch (action != null ? action : "") {
            case "enable" -> {
                healthTracker.markEnabled(name);
                yield new BackendActionResult("enabled", name);
            }
            case "disable" -> {
                healthTracker.markDisabled(name);
                yield new BackendActionResult("disabled", name);
            }
            default -> throw new IllegalArgumentException("Action must be 'enable' or 'disable'");
        };
    }

    public record BackendActionResult(String status, String backend) {}
}
