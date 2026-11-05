package com.educore.api.system;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final Clock clock;
    private final String version;

    public SystemStatusController(
            Clock clock,
            @Value("${spring.application.version:dev}") String version) {
        this.clock = clock;
        this.version = version;
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        return Map.of(
                "service", "educore-api",
                "status", "ok",
                "version", version,
                "time", Instant.now(clock));
    }
}
