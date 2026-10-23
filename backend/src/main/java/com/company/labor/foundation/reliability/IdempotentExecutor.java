package com.company.labor.foundation.reliability;

import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.web.JsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class IdempotentExecutor {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final TransactionTemplate transaction;

    public IdempotentExecutor(JdbcTemplate jdbc, JsonCodec json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    public <T> T execute(String operation, String requestKey, Object request, Class<T> resultType, Supplier<T> action) {
        requireKey(operation, 100);
        requireKey(requestKey, 200);
        Objects.requireNonNull(resultType, "Result type is required");
        Objects.requireNonNull(action, "Action is required");
        long tenant = ExecutionContext.requireTenant();
        long actor = ExecutionContext.require().actorId();
        String hash = digest(json.canonical(request));
        return transaction.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO foundation_idempotency(tenant_id, actor_id, operation, request_key, request_hash)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, tenant, actor, operation, requestKey, hash);
            if (inserted == 0) {
                return jdbc.queryForObject("""
                        SELECT request_hash, result_json FROM foundation_idempotency
                        WHERE tenant_id = ? AND actor_id = ? AND operation = ? AND request_key = ?
                        """, (row, index) -> {
                            if (!hash.equals(row.getString("request_hash"))) {
                                throw new BusinessException(409, "Idempotency key was used for different request content");
                            }
                            String result = row.getString("result_json");
                            if (result == null) {
                                throw new BusinessException(409, "Idempotent operation is already executing in this transaction");
                            }
                            return json.read(result, resultType);
                        }, tenant, actor, operation, requestKey);
            }
            T result = action.get();
            jdbc.update("""
                    UPDATE foundation_idempotency SET result_json = ?
                    WHERE tenant_id = ? AND actor_id = ? AND operation = ? AND request_key = ?
                    """, json.write(result), tenant, actor, operation, requestKey);
            return result;
        });
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireKey(String value, int maximum) {
        if (value == null || !value.matches("[a-zA-Z0-9:._-]{1," + maximum + "}")) {
            throw new IllegalArgumentException("Invalid operation or idempotency key");
        }
    }
}
