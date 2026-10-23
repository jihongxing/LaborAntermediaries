package com.company.labor.foundation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@Validated
@ConfigurationProperties("foundation")
public class FoundationProperties {
    @Valid
    private Security security = new Security();
    @Valid
    private Crypto crypto = new Crypto();
    @Valid
    private Storage storage = new Storage();
    @Valid
    private Tasks tasks = new Tasks();
    private Set<String> tenantTables = new HashSet<>();
    private Set<String> sharedTables = new HashSet<>();

    @Data
    public static class Security {
        private String publicKey = "";
        @NotBlank
        private String issuer = "labor-identity";
        @NotBlank
        private String audience = "labor-backend";
        private Set<String> allowedOrigins = new HashSet<>();
    }

    @Data
    public static class Crypto {
        private String activeKey = "";
        private Map<String, String> keys = new HashMap<>();
    }

    @Data
    public static class Storage {
        private Path root = Path.of("./var/private-files");
        @Min(1)
        private long maxBytes = 10 * 1024 * 1024;
    }

    @Data
    public static class Tasks {
        private boolean enabled;
        @Min(1)
        private int maxAttempts = 5;
        private Duration lease = Duration.ofMinutes(5);
        private Duration retryDelay = Duration.ofSeconds(30);
    }
}
