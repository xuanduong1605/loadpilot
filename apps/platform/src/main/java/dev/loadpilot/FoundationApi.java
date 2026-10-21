package dev.loadpilot;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FoundationApi {
    @GetMapping("/api/status")
    Map<String, String> status() {
        return Map.of("application", "loadpilot", "stage", "project-setup", "version", "0.1.0-SNAPSHOT");
    }
}
