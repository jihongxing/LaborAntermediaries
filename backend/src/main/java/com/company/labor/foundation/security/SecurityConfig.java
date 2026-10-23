package com.company.labor.foundation.security;

import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.context.ExecutionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(FoundationProperties properties, ResourceLoader resources,
                                 TokenRevocations revocations) throws IOException {
        FoundationProperties.Security settings = properties.getSecurity();
        if (settings.getPublicKey().isBlank()) {
            return token -> {
                throw new BadJwtException("JWT verification is not configured");
            };
        }
        RSAPublicKey publicKey;
        try (InputStream input = resources.getResource(settings.getPublicKey()).getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(input);
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> required = jwt -> validateClaims(jwt, settings, revocations);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.getIssuer()), required));
        return decoder;
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt, FoundationProperties.Security settings,
                                                       TokenRevocations revocations) {
        try {
            if (jwt.getExpiresAt() == null || jwt.getId() == null || jwt.getId().isBlank()
                    || jwt.getId().length() > 200 || jwt.getAudience() == null
                    || !jwt.getAudience().contains(settings.getAudience())
                    || Long.parseLong(jwt.getSubject()) <= 0) {
                return invalidToken();
            }
            Object tenant = jwt.getClaims().get("tenant_id");
            if (tenant != null && Long.parseLong(tenant.toString()) <= 0) {
                return invalidToken();
            }
            Object permissions = jwt.getClaims().get("permissions");
            if (permissions != null) {
                if (!(permissions instanceof List<?> values) || values.stream()
                        .anyMatch(value -> !(value instanceof String text) || !text.matches("[a-zA-Z0-9:._-]{1,100}"))) {
                    return invalidToken();
                }
            }
            return revocations.isRevoked(jwt.getId()) ? invalidToken() : OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return invalidToken();
        } catch (DataAccessException exception) {
            throw new JwtException("Token verification is unavailable");
        }
    }

    private OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token rejected", null));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HttpErrorWriter errors) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access -> access
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("foundation:observe")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errors.write(response, 401, "Authentication required");
                        })
                        .accessDeniedHandler((request, response, exception) -> errors.write(response, 403, "Permission denied")))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errors.write(response, 401, "Invalid or expired credentials");
                        }))
                .addFilterAfter(new IdentityFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(FoundationProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.copyOf(properties.getSecurity().getAllowedOrigins()));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static class IdentityFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
                Jwt jwt = authentication.getToken();
                Object tenant = jwt.getClaims().get("tenant_id");
                Set<String> permissions = authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority()).collect(Collectors.toUnmodifiableSet());
                ExecutionContext.Identity identity = new ExecutionContext.Identity(Long.parseLong(jwt.getSubject()),
                        tenant == null ? null : Long.valueOf(tenant.toString()), permissions);
                try (ExecutionContext.Scope ignored = ExecutionContext.open(identity, MDC.get("requestId"))) {
                    chain.doFilter(request, response);
                }
            } else {
                chain.doFilter(request, response);
            }
        }
    }
}
