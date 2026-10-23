package com.company.labor.foundation;

import com.company.labor.foundation.crypto.CryptoService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ProductionChecks implements InitializingBean {
    private final FoundationProperties properties;
    private final CryptoService crypto;
    private final Environment environment;

    public ProductionChecks(FoundationProperties properties, CryptoService crypto, Environment environment) {
        this.properties = properties;
        this.crypto = crypto;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.getTasks().getLease().isNegative() || properties.getTasks().getLease().isZero()
                || properties.getTasks().getRetryDelay().isNegative()) {
            throw new IllegalStateException("Task lease must be positive and retry delay must not be negative");
        }
        if (properties.getSecurity().getAllowedOrigins().contains("*")) {
            throw new IllegalStateException("CORS requires explicit origins");
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            if (properties.getSecurity().getPublicKey().isBlank() || !crypto.isConfigured()
                    || !properties.getStorage().getRoot().isAbsolute()) {
                throw new IllegalStateException("Production requires JWT verification, encryption and an absolute private storage path");
            }
        }
    }
}
