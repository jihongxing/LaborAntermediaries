package com.company.labor.foundation.audit;

import com.company.labor.foundation.context.ExecutionContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 将标识化审计事件写入数据库，使用调用线程的身份和请求标识，不接受任意敏感正文。 */
@Service
public class AuditRecorder {
    /** 审计持久化入口，写入参与调用方当前数据库事务。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建审计记录器。
     * @param jdbc 数据库访问模板
     */
    public AuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入当前操作者的审计事件；不新开独立事务，调用方回滚时记录也可能回滚。
     * @param action 操作标识，最长 100 字符
     * @param resourceType 资源类型标识，最长 100 字符
     * @param resourceId 资源标识，最长 200 字符，不得携带敏感正文
     * @param outcome SUCCESS、DENIED 或 FAILED
     * @throws IllegalArgumentException 标识或结果不合法
     * @throws com.company.labor.common.exception.BusinessException 当前未认证
     */
    public void record(String action, String resourceType, String resourceId, String outcome) {
        requireIdentifier(action, 100);
        requireIdentifier(resourceType, 100);
        requireIdentifier(resourceId, 200);
        if (!Set.of("SUCCESS", "DENIED", "FAILED").contains(outcome)) {
            throw new IllegalArgumentException("Invalid audit outcome");
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        jdbc.update("""
                INSERT INTO foundation_audit(actor_id, tenant_id, action, resource_type, resource_id, outcome, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, identity.actorId(), identity.tenantId(), action, resourceType, resourceId, outcome, MDC.get("requestId"));
    }

    /**
     * 限制审计字段的字符集和长度，减少误记正文与注入日志内容的风险。
     * @param value 待校验标识
     * @param maximum 最大字符数
     * @throws IllegalArgumentException 标识为空或格式不合法
     */
    private void requireIdentifier(String value, int maximum) {
        if (value == null || !value.matches("[a-zA-Z0-9:._-]{1," + maximum + "}")) {
            throw new IllegalArgumentException("Audit fields must contain identifiers, not arbitrary sensitive content");
        }
    }
}
