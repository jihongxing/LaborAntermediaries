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

@Configuration
public class MyBatisPlusConfig {

    @Bean
    public TenantLineHandler tenantLineHandler(FoundationProperties properties) {
        Set<String> tenantTables = normalized(properties.getTenantTables());
        Set<String> sharedTables = normalized(properties.getSharedTables());
        if (tenantTables.stream().anyMatch(sharedTables::contains)) {
            throw new IllegalStateException("A table cannot be both isolated and shared");
        }
        return new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(ExecutionContext.requireTenant());
            }

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

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler) {
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

            @Override
            protected void processUpdate(Update update, int index, String sql, Object context) {
                if (!tenantLineHandler.ignoreTable(update.getTable().getName())
                        && update.getUpdateSets().stream().flatMap(set -> set.getColumns().stream())
                        .anyMatch(column -> isTenantColumn(column.getColumnName()))) {
                    throw new IllegalArgumentException("Tenant columns cannot be reassigned");
                }
                super.processUpdate(update, index, sql, context);
            }

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
