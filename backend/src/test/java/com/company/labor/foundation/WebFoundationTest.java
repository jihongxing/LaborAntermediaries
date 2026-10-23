package com.company.labor.foundation;

import com.company.labor.common.context.UserContext;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.filter.RequestIdFilter;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.crypto.CryptoService;
import com.company.labor.foundation.security.HttpErrorWriter;
import com.company.labor.foundation.web.JsonBodyLimitFilter;
import com.company.labor.foundation.web.JsonCodec;
import com.company.labor.foundation.web.WebConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WebFoundationTest {
    @Test
    void chunkedJsonIsLimitedAndSmallBodiesRemainReadable() throws Exception {
        JsonBodyLimitFilter filter = new JsonBodyLimitFilter(new HttpErrorWriter(new ObjectMapper()));
        MockHttpServletRequest large = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        large.setContentType("application/problem+json");
        large.setContent(new byte[JsonBodyLimitFilter.MAX_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(large, response, (request, result) -> invoked.set(true));
        assertFalse(invoked.get());
        assertEquals(413, response.getStatus());
        MockHttpServletRequest small = new MockHttpServletRequest();
        small.setContentType("application/json");
        small.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filter.doFilter(small, new MockHttpServletResponse(),
                (request, result) -> assertArrayEquals(small.getContentAsByteArray(), request.getInputStream().readAllBytes()));
        MockHttpServletRequest readerRequest = new MockHttpServletRequest();
        readerRequest.setContentType("application/json");
        readerRequest.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filter.doFilter(readerRequest, new MockHttpServletResponse(),
                (request, result) -> assertEquals("{}", request.getReader().readLine()));
    }

    @Test
    void requestScopeIsCleanedOnFailureAndUnsafeRequestIdsAreReplaced() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "unsafe\r\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> new RequestIdFilter().doFilter(request, response, (input, output) -> {
            ExecutionContext.open(new ExecutionContext.Identity(1, 2L, Set.of()), "scope");
            throw new IllegalStateException("test");
        }));
        assertTrue(response.getHeader("X-Request-Id").matches("[A-Za-z0-9_-]{1,64}"));
        assertNull(MDC.get("requestId"));
        assertNull(UserContext.getUserOrNull());
        assertThrows(BusinessException.class, ExecutionContext::require);
    }

    @Test
    void allLongValuesAreStringsAndJsonCodecFailsFast() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new WebConfig().longSerialization().customize(builder);
        ObjectMapper mapper = builder.build();
        record Values(long primitive, Long boxed) {
        }
        assertEquals("{\"primitive\":\"9007199254740993\",\"boxed\":\"9007199254740993\"}",
                mapper.writeValueAsString(new Values(9007199254740993L, 9007199254740993L)));
        JsonCodec codec = new JsonCodec(mapper);
        assertEquals(codec.canonical(Map.of("b", 2, "a", 1)), codec.canonical(Map.of("a", 1, "b", 2)));
        assertThrows(IllegalArgumentException.class, () -> codec.read("{", Values.class));
    }

    @Test
    void productionAndUnsafeTaskConfigurationFailClosed() {
        FoundationProperties properties = new FoundationProperties();
        CryptoService crypto = new CryptoService(properties);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new ProductionChecks(properties, crypto, environment).afterPropertiesSet());
        environment.setActiveProfiles("local");
        properties.getSecurity().setAllowedOrigins(Set.of("*"));
        assertThrows(IllegalStateException.class, () -> new ProductionChecks(properties, crypto, environment).afterPropertiesSet());
        properties.getSecurity().setAllowedOrigins(Set.of());
        properties.getTasks().setLease(Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> new ProductionChecks(properties, crypto, environment).afterPropertiesSet());
    }
}
