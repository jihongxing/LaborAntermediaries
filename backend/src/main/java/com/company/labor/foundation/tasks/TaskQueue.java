package com.company.labor.foundation.tasks;

import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.web.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class TaskQueue {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final FoundationProperties.Tasks settings;
    private final Map<String, TaskHandler> handlers;
    private final TransactionTemplate transaction;

    public record Status(long id, String type, String state, int attempts, Instant updatedAt) {
    }

    private record Claimed(long id, long tenant, long actor, String type, String payload, String requestId,
                           int attempts, String leaseToken) {
    }

    public TaskQueue(JdbcTemplate jdbc, JsonCodec json, FoundationProperties properties,
                     List<TaskHandler> handlers, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.settings = properties.getTasks();
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Map<String, TaskHandler> registered = new HashMap<>();
        for (TaskHandler handler : handlers) {
            requireType(handler.type());
            if (registered.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("Duplicate task handler type");
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public long enqueue(String type, Object payload) {
        requireType(type);
        if (!handlers.containsKey(type)) {
            throw new IllegalArgumentException("Task handler is not registered");
        }
        long tenant = ExecutionContext.requireTenant();
        String encoded = json.write(payload);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Task payload exceeds 64 KiB");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO foundation_task(tenant_id, actor_id, task_type, payload, request_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, tenant, ExecutionContext.require().actorId(), type, encoded, MDC.get("requestId"));
        if (id == null) {
            throw new IllegalStateException("Task was not persisted");
        }
        return id;
    }

    public Status status(long id) {
        List<Status> result = jdbc.query("""
                SELECT id, task_type, status, attempts, updated_at FROM foundation_task
                WHERE id = ? AND tenant_id = ? AND actor_id = ?
                """, (row, index) -> new Status(row.getLong("id"), row.getString("task_type"), row.getString("status"),
                row.getInt("attempts"), row.getTimestamp("updated_at").toInstant()),
                id, ExecutionContext.requireTenant(), ExecutionContext.require().actorId());
        if (result.isEmpty()) {
            throw new BusinessException(404, "Task not found");
        }
        return result.get(0);
    }

    @Scheduled(fixedDelayString = "${foundation.tasks.poll-delay-ms:1000}")
    public void poll() {
        if (settings.isEnabled()) {
            try {
                runNext();
            } catch (RuntimeException exception) {
                log.error("Task polling failed type={}", exception.getClass().getSimpleName());
            }
        }
    }

    public boolean runNext() {
        Claimed task = transaction.execute(status -> claim());
        if (task == null) {
            return false;
        }
        ExecutionContext.Identity identity = new ExecutionContext.Identity(task.actor(), task.tenant(), Set.of());
        try (ExecutionContext.Scope ignored = ExecutionContext.open(identity, task.requestId())) {
            try {
                TaskHandler handler = handlers.get(task.type());
                if (handler == null) {
                    throw new IllegalStateException("Task handler is not registered");
                }
                handler.execute(task.id(), task.payload());
                finish(task, null);
            } catch (Exception exception) {
                finish(task, exception.getClass().getSimpleName());
                log.warn("Task execution failed id={} attempt={} type={}", task.id(), task.attempts(),
                        exception.getClass().getSimpleName());
            }
        }
        return true;
    }

    private Claimed claim() {
        jdbc.update("""
                UPDATE foundation_task SET status = 'FAILED', last_error = 'LeaseExpired', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP AND attempts >= ?
                """, settings.getMaxAttempts());
        List<Claimed> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM foundation_task
                    WHERE ((status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP)
                        OR (status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP)) AND attempts < ?
                    ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE foundation_task AS task
                SET status = 'RUNNING', attempts = attempts + 1,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    lease_token = ?, updated_at = CURRENT_TIMESTAMP
                FROM candidate WHERE task.id = candidate.id RETURNING task.*
                """, (row, index) -> claimed(row), settings.getMaxAttempts(),
                settings.getLease().toMillis(), UUID.randomUUID().toString());
        return claimed.isEmpty() ? null : claimed.get(0);
    }

    private Claimed claimed(ResultSet row) throws SQLException {
        return new Claimed(row.getLong("id"), row.getLong("tenant_id"), row.getLong("actor_id"),
                row.getString("task_type"), row.getString("payload"), row.getString("request_id"),
                row.getInt("attempts"), row.getString("lease_token"));
    }

    private void finish(Claimed task, String error) {
        String state = error == null ? "SUCCEEDED" : task.attempts() >= settings.getMaxAttempts() ? "FAILED" : "PENDING";
        int changed = jdbc.update("""
                UPDATE foundation_task SET status = ?, last_error = ?, lease_until = NULL, lease_token = NULL,
                    available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, state, error, settings.getRetryDelay().toMillis(), task.id(), task.leaseToken());
        if (changed == 0) {
            log.warn("Task lease was replaced before completion id={}", task.id());
        }
    }

    private void requireType(String type) {
        if (type == null || !type.matches("[a-z][a-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("Invalid task type");
        }
    }
}
