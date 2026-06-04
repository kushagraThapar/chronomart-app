package com.chronomart.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    /**
     * Lightweight liveness/readiness for Docker healthcheck + Spring Boot actuator-free probes.
     * Spring Boot Actuator's {@code /actuator/health} is also enabled in application.yaml.
     */
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of("ok", true, "service", "chronomart-java");
    }
}
