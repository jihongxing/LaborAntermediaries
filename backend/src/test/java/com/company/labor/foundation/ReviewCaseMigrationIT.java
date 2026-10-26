package com.company.labor.foundation;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 在独立 PostgreSQL schema 中验证 V18/V19 对迁移前事项和任务的保守回填。 */
class ReviewCaseMigrationIT {
    /** 集成测试数据库必须只指向专用 `_test` 数据库。 */
    private static final String DATABASE_URL_ENV = "TEST_DATABASE_URL";
    /** 数据库连接账号的环境变量名。 */
    private static final String DATABASE_USERNAME_ENV = "TEST_DATABASE_USERNAME";
    /** 数据库连接密码的环境变量名。 */
    private static final String DATABASE_PASSWORD_ENV = "TEST_DATABASE_PASSWORD";

    /**
     * 验证迁移只为非草稿组织和需求创建 LEGACY_GAP 事项，并为当前待审核版本建立任务。
     * @throws Exception 数据库迁移、夹具写入或断言查询失败时抛出
     */
    @Test
    void migrationMarksOnlyExistingSubmittedResourcesAsLegacyGap() throws Exception {
        String url = System.getenv(DATABASE_URL_ENV);
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+_test")) {
            throw new IllegalStateException("TEST_DATABASE_URL must name a dedicated PostgreSQL database ending in _test");
        }
        String username = requiredEnvironment(DATABASE_USERNAME_ENV);
        String password = requiredEnvironment(DATABASE_PASSWORD_ENV);
        String schema = "g5_review_task_" + UUID.randomUUID().toString().replace("-", "");

        try {
            migrationRunner(url, username, password, schema, "17").migrate();
            seedPreV18Resources(url, username, password, schema);
            migrationRunner(url, username, password, schema, null).migrate();
            assertLegacyGapBackfill(url, username, password, schema);
        } finally {
            dropSchema(url, username, password, schema);
        }
    }

    /**
     * 创建指定目标版本的 Flyway 实例；空目标表示迁移至当前全部版本。
     * @param url 隔离测试数据库 JDBC 地址
     * @param username 隔离测试数据库账号
     * @param password 隔离测试数据库密码
     * @param schema 本次测试独占的 schema
     * @param targetVersion 可选的最高迁移版本
     * @return 配置完成且尚未执行的迁移器
     */
    private Flyway migrationRunner(String url, String username, String password, String schema,
                                   String targetVersion) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        return configuration.load();
    }

    /**
     * 在 V17 schema 中建立已提交及草稿组织/需求，模拟升级前数据库现状。
     * @param url 隔离测试数据库 JDBC 地址
     * @param username 隔离测试数据库账号
     * @param password 隔离测试数据库密码
     * @param schema 本次测试独占的 schema
     * @throws Exception 数据库连接或合成夹具写入失败时抛出
     */
    private void seedPreV18Resources(String url, String username, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + quoteIdentifier(schema));
            statement.executeUpdate("""
                    INSERT INTO business_organization
                        (id, tenant_id, organization_role, legal_name, registration_id,
                         authorization_reference, status, created_by)
                    VALUES
                        (1001, 1001, 'EMPLOYER', '合成已提交组织', 'LEGACY-EMPLOYER',
                         'SYNTHETIC', 'PENDING_REVIEW', 501),
                        (1002, 1002, 'EMPLOYER', '合成草稿组织', 'DRAFT-EMPLOYER',
                         'SYNTHETIC', 'DRAFT', 502)
                    """);
            statement.executeUpdate("""
                    INSERT INTO business_demand
                        (id, organization_id, tenant_id, status, created_by)
                    VALUES
                        (2001, 1001, 1001, 'PENDING_REVIEW', 501),
                        (2002, 1001, 1001, 'DRAFT', 501)
                    """);
        }
    }

    /**
     * 检查回填数量、草稿排除以及未知历史字段均保持为空。
     * @param url 隔离测试数据库 JDBC 地址
     * @param username 隔离测试数据库账号
     * @param password 隔离测试数据库密码
     * @param schema 本次测试独占的 schema
     * @throws Exception 数据库连接或验收查询失败时抛出
     */
    private void assertLegacyGapBackfill(String url, String username, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + quoteIdentifier(schema));
            try (ResultSet cases = statement.executeQuery("""
                    SELECT resource_type, resource_id, initial_version, opened_by, opened_at, history_coverage
                    FROM platform_review_case ORDER BY resource_type, resource_id
                    """)) {
                int rows = 0;
                while (cases.next()) {
                    rows++;
                    assertNull(cases.getObject("initial_version"));
                    assertNull(cases.getObject("opened_by"));
                    assertNull(cases.getObject("opened_at"));
                    assertEquals("LEGACY_GAP", cases.getString("history_coverage"));
                    if ("ORGANIZATION".equals(cases.getString("resource_type"))) {
                        assertEquals(1001L, cases.getLong("resource_id"));
                    } else {
                        assertEquals("DEMAND", cases.getString("resource_type"));
                        assertEquals(2001L, cases.getLong("resource_id"));
                    }
                }
                assertEquals(2, rows);
            }
            try (ResultSet tasks = statement.executeQuery("""
                    SELECT task_type, resource_type, resource_id, resource_version, status
                    FROM platform_review_task ORDER BY resource_type, resource_id
                    """)) {
                int rows = 0;
                while (tasks.next()) {
                    rows++;
                    assertEquals(1L, tasks.getLong("resource_version"));
                    assertEquals("PENDING", tasks.getString("status"));
                    if ("ORGANIZATION".equals(tasks.getString("resource_type"))) {
                        assertEquals("ORGANIZATION_REVIEW", tasks.getString("task_type"));
                        assertEquals(1001L, tasks.getLong("resource_id"));
                    } else {
                        assertEquals("DEMAND", tasks.getString("resource_type"));
                        assertEquals("DEMAND_REVIEW", tasks.getString("task_type"));
                        assertEquals(2001L, tasks.getLong("resource_id"));
                    }
                }
                assertEquals(2, rows);
            }
            assertEquals(0, count(statement,
                    "SELECT COUNT(*) FROM platform_review_task WHERE resource_id IN (1002, 2002)"));
        }
    }

    /**
     * 执行只引用合成编号的迁移计数断言。
     * @param statement 当前专属 schema 的 SQL 语句
     * @param sql 固定测试查询
     * @return 查询行数
     * @throws Exception 数据库查询失败
     */
    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * 删除本次测试创建的 schema，避免测试产物残留在共享隔离数据库中。
     * @param url 隔离测试数据库 JDBC 地址
     * @param username 隔离测试数据库账号
     * @param password 隔离测试数据库密码
     * @param schema 本次测试独占的 schema
     * @throws Exception 数据库连接或 schema 清理失败时抛出
     */
    private void dropSchema(String url, String username, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        }
    }

    /**
     * 读取必需的数据库环境变量并在缺少时给出无敏感值的错误。
     * @param name 环境变量名
     * @return 非空环境变量值
     */
    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }

    /**
     * 为受控生成的 PostgreSQL schema 名称添加双引号。
     * @param identifier schema 标识符
     * @return 可用于 SQL 的引用标识符
     */
    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
