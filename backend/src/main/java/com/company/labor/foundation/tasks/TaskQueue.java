package com.company.labor.foundation.tasks;

import com.company.labor.common.error.PlatformError;

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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

/**
 * PostgreSQL 持久化任务队列，通过行锁、租约与租约令牌协调多消费者。
 * 提供可重试处理，不保证恰好执行一次，也不自动续租，处理器必须容忍重复执行。
 */
@Slf4j
@Service
public class TaskQueue {
    /** 任务状态与载荷持久化入口。 */
    private final JdbcTemplate jdbc;
    /** 入队载荷的 JSON 序列化器。 */
    private final JsonCodec json;
    /** 消费开关、最多尝试次数、租约和重试延迟配置。 */
    private final FoundationProperties.Tasks settings;
    /** 按唯一类型索引的不可变处理器表。 */
    private final Map<String, TaskHandler> handlers;
    /** 在独立短事务中完成认领，避免执行处理器期间长时间持有任务行锁。 */
    private final TransactionTemplate transaction;

    /**
     * 向当前组织内原始提交者返回的任务状态，不暴露载荷与异常正文。
     * @param id 任务标识
     * @param type 任务类型
     * @param state PENDING、RUNNING、SUCCEEDED 或 FAILED
     * @param attempts 已认领执行的次数
     * @param updatedAt 最近状态更新时间
     */
    public record Status(long id, String type, String state, int attempts, Instant updatedAt) {
    }

    /**
     * 一次成功认领所需的内部快照。
     * @param id 任务标识
     * @param tenant 提交组织
     * @param actor 提交操作者
     * @param type 任务类型
     * @param payload 保存的 JSON 载荷
     * @param requestId 原始请求日志标识
     * @param attempts 包含本次认领的累计尝试次数
     * @param leaseToken 本次认领令牌，防止过期消费者覆盖新租约状态
     */
    private record Claimed(long id, long tenant, long actor, String type, String payload, String requestId,
                           int attempts, String leaseToken) {
    }

    /**
     * 注册处理器并初始化独立认领事务，重复类型会导致启动失败。
     * @param jdbc 任务访问模板
     * @param json JSON 编解码器
     * @param properties 任务配置
     * @param handlers 可用处理器集合
     * @param manager 数据库事务管理器
     * @throws IllegalArgumentException 处理器类型格式不合法
     * @throws IllegalStateException 多个处理器使用同一类型
     */
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

    /**
     * 持久化当前组织与操作者的任务，数据库写入参与调用方现有事务。
     * @param type 已注册任务类型
     * @param payload 序列化为 UTF-8 后不超过 64 KiB 的 JSON 载荷
     * @return 数据库生成的任务标识
     * @throws IllegalArgumentException 类型不合法、处理器未注册或载荷超限
     * @throws IllegalStateException 数据库未返回任务标识
     * @throws BusinessException 当前未认证或没有组织范围
     */
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

    /**
     * 将任务作为同库事务内的可靠投递记录写入，禁止无事务时静默单独提交。
     * 适合业务变更与通知任务同提交；消费仍至少一次，不能据此承诺外部付款恰好一次。
     * @param type 已注册的任务类型
     * @param payload 不含敏感明文的任务标识载荷
     * @return 已入队的任务标识
     * @throws IllegalTransactionStateException 未开启数据库事务
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueueTransactional(String type, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("An active database transaction is required");
        }
        return enqueue(type, payload);
    }

    /**
     * 查询当前组织中由当前操作者提交的任务，不向其他操作者暴露状态。
     * @param id 任务标识
     * @return 任务状态
     * @throws BusinessException 未认证、没有组织范围或查不到可见任务
     */
    public Status status(long id) {
        List<Status> result = jdbc.query("""
                SELECT id, task_type, status, attempts, updated_at FROM foundation_task
                WHERE id = ? AND tenant_id = ? AND actor_id = ?
                """, (row, index) -> new Status(row.getLong("id"), row.getString("task_type"), row.getString("status"),
                row.getInt("attempts"), row.getTimestamp("updated_at").toInstant()),
                id, ExecutionContext.requireTenant(), ExecutionContext.require().actorId());
        if (result.isEmpty()) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return result.get(0);
    }

    /**
     * 按配置定时尝试消费一个任务，默认间隔 1000 毫秒，仅在 enabled 为 true 时生效。
     * 捕获本轮运行异常以保留后续轮询，仅记录异常类型，不输出载荷。
     */
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

    /**
     * 认领并执行一个可用任务，可由测试或显式调用触发，不检查 enabled 开关。
     * 处理器失败时记录重试状态；数据库认领或完成写入错误仍可能向调用方传播。
     * @return 已认领任务时为 true，没有可用任务时为 false；不代表业务一定成功
     */
    public boolean runNext() {
        Claimed task = transaction.execute(status -> claim());
        if (task == null) {
            return false;
        }
        // 不持久化或恢复请求权限，避免过期权限在后台被永久重放。
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

    /**
     * 在独立事务内收尾达到上限的过期任务，并原子认领一个到期候选。
     * @return 包含本次租约令牌的认领快照，无候选时为空
     */
    private Claimed claim() {
        jdbc.update("""
                UPDATE foundation_task SET status = 'FAILED', last_error = 'LeaseExpired', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP AND attempts >= ?
                """, settings.getMaxAttempts());
        // SKIP LOCKED 避免消费者互相等待；过期任务可重领，因此业务副作用仍需幂等。
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

    /**
     * 将认领语句返回的行映射为执行快照。
     * @param row 当前结果行
     * @return 已认领任务
     * @throws SQLException 读取数据库列失败
     */
    private Claimed claimed(ResultSet row) throws SQLException {
        return new Claimed(row.getLong("id"), row.getLong("tenant_id"), row.getLong("actor_id"),
                row.getString("task_type"), row.getString("payload"), row.getString("request_id"),
                row.getInt("attempts"), row.getString("lease_token"));
    }

    /**
     * 按执行结果和尝试次数更新状态，仅允许本次租约持有者写回。
     * @param task 认领时的任务快照
     * @param error 失败异常类型，为空表示成功，不保存异常正文
     */
    private void finish(Claimed task, String error) {
        String state = error == null ? "SUCCEEDED" : task.attempts() >= settings.getMaxAttempts() ? "FAILED" : "PENDING";
        // 旧消费者即使稍后完成，也不能覆盖已经被新消费者认领的任务状态。
        int changed = jdbc.update("""
                UPDATE foundation_task SET status = ?, last_error = ?, lease_until = NULL, lease_token = NULL,
                    available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, state, error, settings.getRetryDelay().toMillis(), task.id(), task.leaseToken());
        if (changed == 0) {
            log.warn("Task lease was replaced before completion id={}", task.id());
        }
    }

    /**
     * 限制任务类型字符集，避免把任意正文或敏感信息作为路由标识。
     * @param type 待检查类型
     * @throws IllegalArgumentException 类型为空或不符合最多 100 字符的命名规则
     */
    private void requireType(String type) {
        if (type == null || !type.matches("[a-z][a-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("Invalid task type");
        }
    }
}
