package com.company.labor.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.company.labor.common.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Plus配置
 *
 * <p>配置内容：
 * <ul>
 *   <li>分页插件（PaginationInnerInterceptor）</li>
 *   <li>租户插件（TenantLineInnerInterceptor）- 自动在SQL中注入tenant_id</li>
 * </ul>
 *
 * <p>租户插件工作原理：
 * <pre>
 * 原始SQL：SELECT * FROM employee WHERE name = ?
 * 注入后：SELECT * FROM employee WHERE name = ? AND tenant_id = 1001
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * MyBatis Plus拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户插件（必须在分页插件之前）
        interceptor.addInnerInterceptor(tenantLineInnerInterceptor());

        // 分页插件
        interceptor.addInnerInterceptor(paginationInnerInterceptor());

        return interceptor;
    }

    /**
     * 租户插件
     */
    private TenantLineInnerInterceptor tenantLineInnerInterceptor() {
        return new TenantLineInnerInterceptor() {
            /**
             * 获取租户ID值表达式
             */
            @Override
            public Expression getTenantId() {
                // 从TenantContext中获取租户ID
                Long tenantId = TenantContext.getTenantIdOrNull();
                if (tenantId == null) {
                    // 如果租户ID为空，返回-1（查询不到任何数据）
                    return new LongValue(-1);
                }
                return new LongValue(tenantId);
            }

            /**
             * 租户字段名
             */
            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            /**
             * 判断是否忽略租户
             *
             * @param tableName 表名
             * @return true忽略，false不忽略
             */
            @Override
            public boolean ignoreTable(String tableName) {
                // 系统表不需要租户隔离
                if (tableName.equals("sys_user")
                    || tableName.equals("sys_role")
                    || tableName.equals("sys_permission")
                    || tableName.equals("sys_tenant")) {
                    return true;
                }

                // 字典表不需要租户隔离（全局共享）
                if (tableName.startsWith("dict_")) {
                    return true;
                }

                // 其他表都需要租户隔离
                return false;
            }
        };
    }

    /**
     * 分页插件
     */
    private PaginationInnerInterceptor paginationInnerInterceptor() {
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor();

        // 数据库类型（PostgreSQL）
        paginationInterceptor.setDbType(DbType.POSTGRE_SQL);

        // 单页最大条数（防止一次查询过多数据）
        paginationInterceptor.setMaxLimit(1000L);

        // 溢出总页数后是否进行处理（true：返回首页，false：继续查询）
        paginationInterceptor.setOverflow(false);

        return paginationInterceptor;
    }
}
