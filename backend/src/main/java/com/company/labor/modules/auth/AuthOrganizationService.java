package com.company.labor.modules.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 查询当前账号在组织申请和成员审批中的状态；业务资料仍由组织授权接口按需读取。 */
@Service
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthOrganizationService {
    /** 认证和组织状态查询模板。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建账号组织状态查询服务。
     * @param jdbc 组织及成员状态数据访问入口
     */
    public AuthOrganizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 列出账号参与或申请加入的组织，只返回认证中心需要展示的状态字段。
     * @param accountId 来自已验证 JWT 的账号编号
     * @return 按最近状态变化排序的组织状态列表
     */
    public List<AuthDtos.OrganizationEntry> list(long accountId) {
        return jdbc.query("""
                SELECT o.id, o.organization_role, o.status AS organization_status,
                       m.status AS membership_status, m.member_role,
                       r.status AS request_status, r.requested_role
                FROM business_organization o
                LEFT JOIN business_membership m
                  ON m.organization_id = o.id AND m.tenant_id = o.tenant_id AND m.actor_id = ?
                LEFT JOIN LATERAL (
                    SELECT status, requested_role FROM business_membership_request
                    WHERE organization_id = o.id AND tenant_id = o.tenant_id AND actor_id = ?
                    ORDER BY created_at DESC, id DESC LIMIT 1
                ) r ON true
                WHERE o.tenant_id = o.id AND (m.actor_id IS NOT NULL OR r.status IS NOT NULL)
                ORDER BY o.updated_at DESC, o.id
                LIMIT 100
                """, (result, row) -> new AuthDtos.OrganizationEntry(result.getLong("id"),
                result.getString("organization_role"), result.getString("organization_status"),
                result.getString("membership_status"), result.getString("member_role"),
                result.getString("request_status"), result.getString("requested_role")), accountId, accountId);
    }
}
