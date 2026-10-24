package com.company.labor.foundation.reliability;

import com.company.labor.common.error.PlatformError;
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

/**
 * 以组织、操作者、操作和请求键为范围，在同一数据库事务中保存执行结果。
 * 只覆盖事务内可回滚的副作用，不能为外部 HTTP、文件或消息提供恰好一次保证。
 */
@Service
public class IdempotentExecutor {
    /** 幂等记录的数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** 用于请求摘要及结果保存的 JSON 编解码器。 */
    private final JsonCodec json;
    /** 默认参与现有事务，未有事务时新建事务的执行模板。 */
    private final TransactionTemplate transaction;

    /**
     * 创建幂等执行器。
     * @param jdbc 数据库访问模板
     * @param json JSON 编解码器
     * @param manager 与业务写入共用的事务管理器
     */
    public IdempotentExecutor(JdbcTemplate jdbc, JsonCodec json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * 首次调用执行并保存结果，相同范围、键和请求摘要的重试返回已保存结果。
     * 业务异常向外传播，并按事务模板规则回滚，不缓存失败结果。
     * @param <T> 可通过 JSON 还原的结果类型
     * @param operation 最长 100 字符的操作标识
     * @param requestKey 最长 200 字符的幂等键
     * @param request 用于生成请求摘要的数据
     * @param resultType 保存结果的反序列化类型
     * @param action 仅首次执行的业务操作，应将数据库写入纳入同一事务
     * @return 本次执行结果或已缓存结果
     * @throws BusinessException 未认证、没有组织范围，或相同键请求冲突时拒绝
     * @throws IllegalArgumentException 操作标识或幂等键格式不合法
     */
    public <T> T execute(String operation, String requestKey, Object request, Class<T> resultType, Supplier<T> action) {
        requireKey(operation, 100);
        requireKey(requestKey, 200);
        Objects.requireNonNull(resultType, "Result type is required");
        Objects.requireNonNull(action, "Action is required");
        long tenant = ExecutionContext.requireTenant();
        long actor = ExecutionContext.require().actorId();
        String hash = digest(json.canonical(request));
        return transaction.execute(status -> {
            // 由唯一约束仲裁并发，同键插入冲突时不再次执行业务副作用。
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
                                throw new BusinessException(PlatformError.IDEMPOTENCY_CONFLICT);
                            }
                            String result = row.getString("result_json");
                            if (result == null) {
                                // 同一事务内重入可能看到尚未填入结果的记录，不允许再次执行。
                                throw new BusinessException(PlatformError.OPERATION_IN_PROGRESS);
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

    /**
     * 对已序列化的请求内容计算 SHA-256 摘要；这不是敏感内容的加密方案。
     * @param value 请求的稳定 JSON 表示
     * @return 十六进制摘要
     * @throws IllegalStateException 运行环境不支持 SHA-256
     */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 校验操作与请求键的长度和标识字符集。
     * @param value 待校验标识
     * @param maximum 最大字符数
     * @throws IllegalArgumentException 标识为空或格式不合法
     */
    private void requireKey(String value, int maximum) {
        if (value == null || !value.matches("[a-zA-Z0-9:._-]{1," + maximum + "}")) {
            throw new IllegalArgumentException("Invalid operation or idempotency key");
        }
    }
}
