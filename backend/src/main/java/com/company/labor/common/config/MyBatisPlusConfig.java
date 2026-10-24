package com.company.labor.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.context.ExecutionContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 配置 MyBatis 的组织隔离、乐观锁和分页插件。
 *
 * <p>隔离依据来自已认证的 ExecutionContext；仅保护经过本插件的 SQL，不覆盖直接 JDBC 访问。
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 构建默认拒绝未知表的隔离策略，共享表必须显式登记。
     *
     * @param properties 隔离表及共享表配置
     * @return 从执行上下文读取组织标识的隔离处理器
     * @throws IllegalStateException 表名不合法或同一表同时登记为隔离表与共享表
     */
    @Bean
    public TenantLineHandler tenantLineHandler(FoundationProperties properties) {
        Set<String> tenantTables = normalized(properties.getTenantTables());
        Set<String> sharedTables = normalized(properties.getSharedTables());
        if (tenantTables.stream().anyMatch(sharedTables::contains)) {
            throw new IllegalStateException("A table cannot be both isolated and shared");
        }
        return new TenantLineHandler() {
            /**
             * 获取已认证执行上下文中的组织标识，不读取兼容租户上下文。
             *
             * @return 组织标识对应的 SQL 表达式
             * @throws com.company.labor.common.exception.BusinessException 未认证或当前身份没有组织范围
             */
            @Override
            public Expression getTenantId() {
                return new LongValue(ExecutionContext.requireTenant());
            }

            /**
             * 判断表是否可豁免隔离；未知表拒绝访问，隔离表要求存在组织上下文。
             *
             * @param tableName SQL 中的表名
             * @return 仅显式登记的共享表返回 true
             * @throws IllegalStateException 表未登记隔离策略
             * @throws com.company.labor.common.exception.BusinessException 隔离表访问缺少认证身份或组织范围
             */
            @Override
            public boolean ignoreTable(String tableName) {
                String normalized = tableName.toLowerCase(Locale.ROOT);
                if (sharedTables.contains(normalized)) {
                    return true;
                }
                if (!tenantTables.contains(normalized)) {
                    throw new IllegalStateException("MyBatis table has no registered isolation policy: " + tableName);
                }
                ExecutionContext.requireTenant();
                return false;
            }
        };
    }

    /**
     * 按组织隔离、乐观锁、分页的顺序组装插件。
     *
     * @param tenantLineHandler 组织隔离策略
     * @return 单页最多 200 条且越界页不回退首页的 PostgreSQL 插件链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler) {
            /**
             * 拒绝隔离表显式传入组织列或使用 upsert，随后由父类补充组织条件。
             *
             * @param insert 插入语句语法树
             * @param index 当前语句索引
             * @param sql 原始 SQL
             * @param context 插件调用上下文
             * @throws IllegalArgumentException 隔离表插入未列出字段、指定组织列或使用 upsert
             */
            @Override
            protected void processInsert(Insert insert, int index, String sql, Object context) {
                if (!tenantLineHandler.ignoreTable(insert.getTable().getName())) {
                    if (insert.getColumns() == null || insert.getColumns().stream()
                            .anyMatch(column -> isTenantColumn(column.getColumnName()))
                            || insert.getConflictAction() != null || insert.isUseDuplicate()) {
                        throw new IllegalArgumentException("Tenant columns and upserts must not be supplied by isolated-table inserts");
                    }
                }
                super.processInsert(insert, index, sql, context);
            }

            /**
             * 阻止更新组织归属，避免通过改写组织列迁移数据。
             *
             * @param update 更新语句语法树
             * @param index 当前语句索引
             * @param sql 原始 SQL
             * @param context 插件调用上下文
             * @throws IllegalArgumentException 更新隔离表时试图改写组织列
             */
            @Override
            protected void processUpdate(Update update, int index, String sql, Object context) {
                if (!tenantLineHandler.ignoreTable(update.getTable().getName())
                        && update.getUpdateSets().stream().flatMap(set -> set.getColumns().stream())
                        .anyMatch(column -> isTenantColumn(column.getColumnName()))) {
                    throw new IllegalArgumentException("Tenant columns cannot be reassigned");
                }
                super.processUpdate(update, index, sql, context);
            }

            /**
             * 忽略双引号及大小写后识别组织列。
             *
             * @param name SQL 列名
             * @return 是否为隔离策略指定的组织列
             */
            private boolean isTenantColumn(String name) {
                return name.replace("\"", "").equalsIgnoreCase(tenantLineHandler.getTenantIdColumn());
            }
        });
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 校验未带 schema 的简单表名并转换为小写不可变集合。
     *
     * @param names 配置中的表名集合
     * @return 使用根区域规则标准化后的表名集合
     * @throws IllegalStateException 表名包含限定符或不符合标识符规则
     */
    private Set<String> normalized(Set<String> names) {
        Set<String> result = new HashSet<>();
        for (String name : names) {
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new IllegalStateException("Use unqualified table names in isolation configuration");
            }
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }
}
