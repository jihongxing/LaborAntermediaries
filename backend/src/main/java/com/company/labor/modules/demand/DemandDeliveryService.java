package com.company.labor.modules.demand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 将已发布需求按字段白名单投递到合格供应商租户。
 * 跨组织读取不查询源租户业务表，投递记录由目标租户独立保存并承担可见性边界。
 */
@Service
public class DemandDeliveryService {
    /** 投递快照持久化入口。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建需求投递服务。
     * @param jdbc 数据库访问模板
     */
    public DemandDeliveryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 将指定需求版本投递给发布时已通过准入的劳务供应商。
     * 操作在调用方发布事务中执行，重复投递由目标租户、需求和版本唯一键吸收。
     * @param demandId 需求标识
     * @param sourceTenantId 需求所属用工方租户
     * @param revision 要投递的已审核版本
     */
    public void deliver(long demandId, long sourceTenantId, long revision) {
        jdbc.update("""
                INSERT INTO business_demand_delivery
                    (tenant_id, source_tenant_id, demand_id, revision, title, quantity, quantity_unit,
                     starts_at, ends_at, bid_deadline, confirmation_deadline, service_scope, location, target, pricing_mode,
                     measurement_basis, settlement_cycle, selection_rule)
                SELECT supplier.id, d.tenant_id, d.id, r.revision, r.title, r.quantity, r.quantity_unit,
                       r.starts_at, r.ends_at, r.bid_deadline, r.confirmation_deadline, r.service_scope, r.location, r.target,
                       r.pricing_mode, r.measurement_basis, r.settlement_cycle, r.selection_rule
                FROM business_organization supplier
                JOIN business_demand d ON d.id = ? AND d.tenant_id = ?
                JOIN business_demand_revision r ON r.demand_id = d.id
                    AND r.tenant_id = d.tenant_id AND r.revision = ?
                WHERE supplier.organization_role = 'LABOR_SUPPLIER'
                  AND supplier.status = 'APPROVED'
                  AND supplier.tenant_id <> d.tenant_id
                ON CONFLICT (tenant_id, demand_id, revision) DO NOTHING
                """, demandId, sourceTenantId, revision);
    }
}
