# PostgreSQL 数据库最佳实践

**针对场景**：劳务中介管理系统 - 多租户SaaS平台  
**PostgreSQL版本**：16+  
**最后更新**：2026年10月17日

---

## 📋 目录

1. [核心原则](#核心原则)
2. [表设计规范](#表设计规范)
3. [索引策略](#索引策略)
4. [查询优化](#查询优化)
5. [事务处理](#事务处理)
6. [性能调优](#性能调优)
7. [监控与维护](#监控与维护)
8. [备份与恢复](#备份与恢复)
9. [常见问题](#常见问题)

---

## 🎯 核心原则

### 1. 金额计算精度（核心红线）

```sql
-- ✅ 正确：使用NUMERIC
CREATE TABLE salary_record (
  gross_salary NUMERIC(10,2) NOT NULL,
  net_salary NUMERIC(10,2) NOT NULL,
  
  -- 添加CHECK约束防止负数
  CONSTRAINT check_salary_positive 
    CHECK (gross_salary >= 0 AND net_salary >= 0 AND net_salary <= gross_salary)
);

-- ❌ 错误：禁止使用DOUBLE PRECISION
-- gross_salary DOUBLE PRECISION NOT NULL  -- 有精度问题
```

**原因**：
```sql
-- DOUBLE PRECISION精度问题
SELECT 2490.5::DOUBLE PRECISION - 100.3::DOUBLE PRECISION;  
-- 结果：2390.1999999999998

-- NUMERIC精确计算
SELECT 2490.50::NUMERIC(10,2) - 100.30::NUMERIC(10,2);  
-- 结果：2390.20
```

### 2. 多租户隔离（安全红线）

```sql
-- ✅ 所有业务表必须有tenant_id
CREATE TABLE employee_basic (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,  -- 租户ID（必须）
  name VARCHAR(50) NOT NULL,
  
  -- 外键约束
  CONSTRAINT fk_tenant FOREIGN KEY (tenant_id) 
    REFERENCES tenant(id) ON DELETE CASCADE
);

-- ✅ 复合索引（tenant_id放在第一位）
CREATE INDEX idx_employee_tenant_id ON employee_basic(tenant_id, id);
CREATE INDEX idx_employee_tenant_created ON employee_basic(tenant_id, created_at DESC);

-- ✅ 使用RLS（Row Level Security）双重保护
ALTER TABLE employee_basic ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON employee_basic
  USING (tenant_id = current_setting('app.current_tenant_id')::BIGINT);

-- 应用层设置当前租户ID
SET app.current_tenant_id = 1001;
```

### 3. 审计字段（必须）

```sql
CREATE TABLE xxx (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  
  -- 业务字段
  -- ...
  
  -- 审计字段（必须）
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  created_by BIGINT,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_by BIGINT,
  deleted_at TIMESTAMPTZ  -- 软删除
);

-- 自动更新updated_at的触发器
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_xxx_updated_at 
  BEFORE UPDATE ON xxx
  FOR EACH ROW 
  EXECUTE FUNCTION update_updated_at_column();
```

---

## 📐 表设计规范

### 必备字段模板

```sql
CREATE TABLE table_name (
  -- 主键（必须）
  id BIGSERIAL PRIMARY KEY,
  
  -- 租户ID（必须）
  tenant_id BIGINT NOT NULL,
  
  -- 业务字段
  -- ...
  
  -- 审计字段（必须）
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  created_by BIGINT,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_by BIGINT,
  deleted_at TIMESTAMPTZ,  -- 软删除
  
  -- 外键约束
  CONSTRAINT fk_tenant FOREIGN KEY (tenant_id) 
    REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT fk_created_by FOREIGN KEY (created_by) 
    REFERENCES system_user(id) ON DELETE SET NULL,
  CONSTRAINT fk_updated_by FOREIGN KEY (updated_by) 
    REFERENCES system_user(id) ON DELETE SET NULL
);

-- 必备索引
CREATE INDEX idx_tablename_tenant_id ON table_name(tenant_id);
CREATE INDEX idx_tablename_created_at ON table_name(created_at DESC);
CREATE INDEX idx_tablename_deleted_at ON table_name(deleted_at) 
  WHERE deleted_at IS NOT NULL;  -- 部分索引

-- 注释（必须）
COMMENT ON TABLE table_name IS '表说明';
COMMENT ON COLUMN table_name.id IS '主键';
COMMENT ON COLUMN table_name.tenant_id IS '租户ID';
COMMENT ON COLUMN table_name.created_at IS '创建时间';
COMMENT ON COLUMN table_name.updated_at IS '更新时间';
COMMENT ON COLUMN table_name.deleted_at IS '软删除时间';

-- 触发器（必须）
CREATE TRIGGER update_table_name_updated_at 
  BEFORE UPDATE ON table_name
  FOR EACH ROW 
  EXECUTE FUNCTION update_updated_at_column();
```

### 字段类型选择

| 数据类型 | PostgreSQL类型 | Java类型 | 说明 |
|---------|---------------|---------|------|
| **主键** | `BIGSERIAL` | `Long` | 自增主键 |
| **外键** | `BIGINT` | `Long` | 外键统一用BIGINT |
| **金额** | `NUMERIC(10,2)` | `BigDecimal` | **强制，禁止用DOUBLE PRECISION** |
| **日期时间** | `TIMESTAMPTZ` | `LocalDateTime` | 带时区的时间戳 |
| **日期** | `DATE` | `LocalDate` | 仅日期 |
| **时间** | `TIME` | `LocalTime` | 仅时间 |
| **布尔** | `BOOLEAN` | `Boolean` | PostgreSQL原生布尔 |
| **枚举** | `VARCHAR(20)` 或 `ENUM` | `String` / `Enum` | 优先用VARCHAR |
| **短字符串** | `VARCHAR(N)` | `String` | N<500 |
| **长文本** | `TEXT` | `String` | N>=500 |
| **JSON数据** | `JSONB` | `String` / `JsonNode` | 半结构化数据，支持索引 |
| **数组** | `ARRAY` | `List<T>` | PostgreSQL原生数组 |
| **UUID** | `UUID` | `UUID` | 全局唯一标识 |

### 命名规范

| 对象 | 规范 | 示例 |
|------|------|------|
| **表名** | 小写+下划线，单数 | `employee_basic` |
| **字段名** | 小写+下划线 | `created_at` |
| **主键** | `id` | `id` |
| **外键字段** | `xxx_id` | `tenant_id`, `project_id` |
| **索引** | `idx_表名_字段名` | `idx_employee_tenant_id` |
| **唯一索引** | `uk_表名_字段名` | `uk_employee_id_card` |
| **外键约束** | `fk_表名_字段名` | `fk_employee_tenant` |
| **CHECK约束** | `check_表名_说明` | `check_salary_positive` |
| **触发器** | `update_表名_字段名` | `update_employee_updated_at` |
| **函数** | `小写_下划线` | `update_updated_at_column` |

---

## 📊 索引策略

### 1. 必须添加索引的字段

```sql
-- ✅ tenant_id（所有业务表）
CREATE INDEX idx_tablename_tenant_id ON table_name(tenant_id);

-- ✅ 外键字段
CREATE INDEX idx_employee_project_id ON employee_basic(project_id);

-- ✅ 状态字段（频繁查询）
CREATE INDEX idx_employee_status ON employee_basic(status);

-- ✅ 时间字段（范围查询）
CREATE INDEX idx_employee_created_at ON employee_basic(created_at DESC);

-- ✅ 频繁作为WHERE条件的字段
CREATE INDEX idx_employee_phone ON employee_basic(phone);
CREATE INDEX idx_employee_id_card ON employee_basic(id_card);
```

### 2. 复合索引（重要）

```sql
-- ✅ tenant_id必须放在第一位
CREATE INDEX idx_employee_tenant_status ON employee_basic(tenant_id, status);
CREATE INDEX idx_employee_tenant_created ON employee_basic(tenant_id, created_at DESC);

-- ✅ 最左前缀原则
-- 查询: WHERE tenant_id = ? AND status = ?  ✅ 使用索引
-- 查询: WHERE tenant_id = ?                ✅ 使用索引
-- 查询: WHERE status = ?                   ❌ 不使用索引
```

### 3. 部分索引（Partial Index）

```sql
-- ✅ 只索引未删除的记录
CREATE INDEX idx_employee_active ON employee_basic(tenant_id, status)
  WHERE deleted_at IS NULL;

-- ✅ 只索引在职员工
CREATE INDEX idx_employee_working ON employee_basic(tenant_id, id)
  WHERE status = 'working' AND deleted_at IS NULL;

-- ✅ 优势：索引更小，查询更快，写入更快
```

### 4. 表达式索引（Expression Index）

```sql
-- ✅ 小写姓名搜索
CREATE INDEX idx_employee_name_lower ON employee_basic(LOWER(name));

-- 查询自动使用索引
SELECT * FROM employee_basic WHERE LOWER(name) = 'zhangsan';

-- ✅ JSON字段索引
CREATE INDEX idx_employee_policy_rate ON employee_basic
  USING GIN ((policy_data->'hourly_rate'));
```

### 5. GIN索引（适用于JSONB、数组、全文搜索）

```sql
-- ✅ JSONB索引
CREATE INDEX idx_employee_policy_gin ON employee_basic USING GIN(policy_data);

-- 查询
SELECT * FROM employee_basic 
WHERE policy_data @> '{"hourly_rate": 15.5}';

-- ✅ 全文搜索索引
CREATE INDEX idx_employee_search ON employee_basic 
  USING GIN(to_tsvector('simple', name || ' ' || phone));

-- 查询
SELECT * FROM employee_basic
WHERE to_tsvector('simple', name || ' ' || phone) @@ to_tsquery('simple', 'zhangsan');
```

### 6. BRIN索引（适用于时序数据）

```sql
-- ✅ BRIN索引特别适合时间序列数据（考勤、工资）
CREATE INDEX idx_attendance_date_brin ON attendance USING BRIN(attendance_date);
CREATE INDEX idx_salary_month_brin ON salary_record USING BRIN(salary_month);

-- 优势：索引极小（数GB表的索引只有数MB），适合大表
-- 适用：数据按时间顺序插入，范围查询频繁
```

### 7. 索引维护

```sql
-- 查看索引使用情况
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY idx_scan;

-- 查看未使用的索引（idx_scan=0）
SELECT schemaname, tablename, indexname
FROM pg_stat_user_indexes
WHERE idx_scan = 0 AND indexrelname NOT LIKE 'pg_toast%';

-- 删除未使用的索引
DROP INDEX idx_xxx;

-- 重建索引（CONCURRENTLY不阻塞读写）
REINDEX INDEX CONCURRENTLY idx_employee_tenant_id;

-- 分析表统计信息
ANALYZE employee_basic;
```

---

## 🚀 查询优化

### 1. 避免SELECT *

```sql
-- ❌ 错误：SELECT *
SELECT * FROM employee_basic WHERE id = 1001;

-- ✅ 正确：指定字段
SELECT id, name, phone, status FROM employee_basic WHERE id = 1001;
```

### 2. 使用EXPLAIN ANALYZE

```sql
-- 分析查询计划
EXPLAIN ANALYZE
SELECT * FROM employee_basic 
WHERE tenant_id = 1001 AND status = 'working';

-- 关键指标：
-- - 执行时间（Execution Time）
-- - 扫描行数（rows）
-- - 是否使用索引（Index Scan vs Seq Scan）
```

### 3. 避免N+1查询

```sql
-- ❌ 错误：N+1查询
-- 1次查询员工，N次查询项目
SELECT * FROM employee_basic WHERE tenant_id = 1001;
-- 然后在Java代码中循环查询每个员工的项目信息

-- ✅ 正确：使用JOIN
SELECT e.*, p.project_name
FROM employee_basic e
LEFT JOIN project p ON e.project_id = p.id
WHERE e.tenant_id = 1001;
```

### 4. 使用EXISTS代替IN（大数据集）

```sql
-- ❌ 性能较差（子查询返回大结果集）
SELECT * FROM employee_basic 
WHERE project_id IN (SELECT id FROM project WHERE tenant_id = 1001);

-- ✅ 性能更好
SELECT * FROM employee_basic e
WHERE EXISTS (
  SELECT 1 FROM project p 
  WHERE p.id = e.project_id AND p.tenant_id = 1001
);
```

### 5. 使用窗口函数

```sql
-- ❌ 复杂的子查询
SELECT e.*,
  (SELECT COUNT(*) FROM employee_basic e2 
   WHERE e2.tenant_id = e.tenant_id AND e2.project_id = e.project_id) as project_count
FROM employee_basic e;

-- ✅ 窗口函数（更快，更简洁）
SELECT e.*,
  COUNT(*) OVER (PARTITION BY tenant_id, project_id) as project_count
FROM employee_basic e;
```

### 6. 使用CTE（WITH子句）

```sql
-- 提高SQL可读性和性能
WITH active_employees AS (
  SELECT * FROM employee_basic 
  WHERE tenant_id = 1001 AND status = 'working' AND deleted_at IS NULL
),
project_summary AS (
  SELECT project_id, COUNT(*) as employee_count
  FROM active_employees
  GROUP BY project_id
)
SELECT p.project_name, ps.employee_count
FROM project_summary ps
JOIN project p ON ps.project_id = p.id;
```

### 7. 并行查询

```sql
-- PostgreSQL自动使用并行查询（大表扫描）
-- 配置参数：
SET max_parallel_workers_per_gather = 4;

-- 查看是否使用并行
EXPLAIN ANALYZE
SELECT COUNT(*) FROM employee_basic WHERE tenant_id = 1001;
-- 查找：Parallel Seq Scan
```

---

## 💾 事务处理

### 1. 事务隔离级别

```sql
-- PostgreSQL默认隔离级别：READ COMMITTED

-- ✅ 工资发放（需要可重复读）
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;

-- 1. 生成工资记录
INSERT INTO salary_record (...) VALUES (...);

-- 2. 扣除借支
UPDATE advance_payment SET status = 'deducted' WHERE employee_id = 1001;

-- 3. 发放记录
INSERT INTO payment_record (...) VALUES (...);

COMMIT;
```

### 2. SAVEPOINT（嵌套事务）

```sql
BEGIN;

-- 主业务
INSERT INTO employee_basic (...) VALUES (...);

SAVEPOINT sp1;

-- 可能失败的操作
BEGIN
  INSERT INTO employee_certificate (...) VALUES (...);
EXCEPTION
  ROLLBACK TO sp1;  -- 回滚到保存点，主业务不受影响
END;

COMMIT;
```

### 3. 悲观锁（FOR UPDATE）

```sql
-- 防止并发更新
BEGIN;

SELECT * FROM employee_basic WHERE id = 1001 FOR UPDATE;

-- 更新操作（其他事务会等待）
UPDATE employee_basic SET status = 'resigned' WHERE id = 1001;

COMMIT;
```

### 4. 乐观锁（版本号）

```sql
-- 表设计
CREATE TABLE employee_basic (
  id BIGSERIAL PRIMARY KEY,
  version INT DEFAULT 0,  -- 版本号
  -- 其他字段...
);

-- 更新时检查版本号
UPDATE employee_basic 
SET name = '张三', version = version + 1
WHERE id = 1001 AND version = 5;

-- 如果影响行数=0，说明被其他事务修改了
```

---

## ⚡ 性能调优

### 1. postgresql.conf核心参数

```ini
# 内存配置（假设服务器16GB内存）
shared_buffers = 4GB              # 25%内存
effective_cache_size = 12GB       # 75%内存
work_mem = 64MB                   # 排序、哈希使用的内存
maintenance_work_mem = 512MB      # VACUUM、CREATE INDEX使用

# 连接数
max_connections = 200             # 最大连接数

# WAL配置（提升写入性能）
wal_level = replica
max_wal_size = 2GB
min_wal_size = 80MB
checkpoint_completion_target = 0.9

# 查询优化
random_page_cost = 1.1            # SSD硬盘建议1.1
effective_io_concurrency = 200    # SSD建议200

# 自动VACUUM
autovacuum = on
autovacuum_max_workers = 4
autovacuum_naptime = 10s

# 并行查询
max_parallel_workers_per_gather = 4
max_parallel_workers = 8
```

### 2. 连接池配置（HikariCP）

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20       # 最大连接数
      minimum-idle: 5             # 最小空闲连接
      connection-timeout: 30000   # 连接超时（ms）
      idle-timeout: 600000        # 空闲超时（ms）
      max-lifetime: 1800000       # 最大生命周期（ms）
      connection-test-query: SELECT 1
```

### 3. VACUUM策略

```sql
-- 手动VACUUM（清理死元组）
VACUUM employee_basic;

-- VACUUM ANALYZE（同时更新统计信息）
VACUUM ANALYZE employee_basic;

-- VACUUM FULL（回收空间，会锁表）
VACUUM FULL employee_basic;

-- 查看表膨胀情况
SELECT schemaname, tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
  n_dead_tup
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC;
```

### 4. 分区表（未来扩展）

```sql
-- Range分区（按月份）
CREATE TABLE salary_record (
  id BIGSERIAL,
  tenant_id BIGINT NOT NULL,
  salary_month DATE NOT NULL,
  -- 其他字段...
  PRIMARY KEY (id, salary_month)
) PARTITION BY RANGE (salary_month);

-- 创建分区
CREATE TABLE salary_record_2024_10 
  PARTITION OF salary_record
  FOR VALUES FROM ('2024-10-01') TO ('2024-11-01');

CREATE TABLE salary_record_2024_11 
  PARTITION OF salary_record
  FOR VALUES FROM ('2024-11-01') TO ('2024-12-01');

-- 自动分区裁剪（只扫描相关分区）
SELECT * FROM salary_record 
WHERE salary_month BETWEEN '2024-10-01' AND '2024-10-31';
```

---

## 📈 监控与维护

### 1. 慢查询监控

```sql
-- 启用pg_stat_statements扩展
CREATE EXTENSION pg_stat_statements;

-- 查看慢查询（耗时>1秒）
SELECT query, calls, total_exec_time, mean_exec_time, max_exec_time
FROM pg_stat_statements
WHERE mean_exec_time > 1000  -- 毫秒
ORDER BY mean_exec_time DESC
LIMIT 20;

-- 重置统计
SELECT pg_stat_statements_reset();
```

### 2. 锁监控

```sql
-- 查看当前锁
SELECT pid, locktype, relation::regclass, mode, granted
FROM pg_locks
WHERE NOT granted;

-- 查看阻塞查询
SELECT blocked_locks.pid AS blocked_pid,
       blocked_activity.usename AS blocked_user,
       blocking_locks.pid AS blocking_pid,
       blocking_activity.usename AS blocking_user,
       blocked_activity.query AS blocked_statement,
       blocking_activity.query AS blocking_statement
FROM pg_catalog.pg_locks blocked_locks
JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
JOIN pg_catalog.pg_locks blocking_locks ON blocking_locks.locktype = blocked_locks.locktype
JOIN pg_catalog.pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
WHERE NOT blocked_locks.granted;

-- 终止阻塞查询
SELECT pg_terminate_backend(blocking_pid);
```

### 3. 连接监控

```sql
-- 查看当前连接数
SELECT count(*) FROM pg_stat_activity;

-- 按数据库分组
SELECT datname, count(*) 
FROM pg_stat_activity 
GROUP BY datname;

-- 按状态分组
SELECT state, count(*) 
FROM pg_stat_activity 
GROUP BY state;

-- 查看空闲连接
SELECT pid, usename, application_name, state, state_change
FROM pg_stat_activity
WHERE state = 'idle'
ORDER BY state_change;
```

### 4. 数据库大小监控

```sql
-- 查看数据库大小
SELECT pg_database.datname,
       pg_size_pretty(pg_database_size(pg_database.datname)) AS size
FROM pg_database
ORDER BY pg_database_size(pg_database.datname) DESC;

-- 查看表大小
SELECT schemaname, tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total_size,
  pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_size,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename) - pg_relation_size(schemaname||'.'||tablename)) AS index_size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

---

## 💾 备份与恢复

### 1. 逻辑备份（pg_dump）

```bash
# 备份单个数据库
pg_dump -h localhost -U postgres -d labor_intermediaries \
  -F c -b -v -f backup_$(date +%Y%m%d).dump

# 恢复
pg_restore -h localhost -U postgres -d labor_intermediaries \
  -v backup_20261017.dump

# 备份单个表
pg_dump -h localhost -U postgres -d labor_intermediaries \
  -t employee_basic -F c -f employee_basic_$(date +%Y%m%d).dump
```

### 2. 物理备份（pg_basebackup）

```bash
# 全量备份
pg_basebackup -h localhost -U replicator -D /backup/pg_basebackup \
  -F t -z -P -X stream

# 优势：备份和恢复速度快，支持PITR（时间点恢复）
```

### 3. 连续归档（WAL归档）

```ini
# postgresql.conf
wal_level = replica
archive_mode = on
archive_command = 'cp %p /backup/wal_archive/%f'
```

### 4. PITR（时间点恢复）

```bash
# 1. 恢复基础备份
tar -xzf base.tar.gz -C /var/lib/postgresql/data

# 2. 创建recovery.conf
restore_command = 'cp /backup/wal_archive/%f %p'
recovery_target_time = '2024-10-17 14:30:00'

# 3. 启动PostgreSQL
pg_ctl start
```

---

## ❓ 常见问题

### 1. 连接数用尽

**问题**：`FATAL: remaining connection slots are reserved for non-replication superuser connections`

**原因**：连接数超过max_connections

**解决**：
```sql
-- 查看当前连接数
SELECT count(*) FROM pg_stat_activity;

-- 查看最大连接数
SHOW max_connections;

-- 终止空闲连接
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'idle';

-- 增加最大连接数（postgresql.conf）
max_connections = 300
```

### 2. 死锁

**问题**：`ERROR: deadlock detected`

**原因**：两个事务互相等待对方释放锁

**解决**：
```sql
-- 查看死锁
SELECT * FROM pg_stat_activity WHERE wait_event_type = 'Lock';

-- 设置死锁超时
SET deadlock_timeout = '1s';

-- 代码层面：统一加锁顺序
```

### 3. 表膨胀

**问题**：表占用空间持续增长，查询变慢

**原因**：UPDATE/DELETE产生死元组，VACUUM不及时

**解决**：
```sql
-- 查看表膨胀
SELECT schemaname, tablename, n_dead_tup, n_live_tup,
  round(n_dead_tup::numeric / (n_live_tup + n_dead_tup) * 100, 2) AS dead_ratio
FROM pg_stat_user_tables
WHERE n_dead_tup > 0
ORDER BY dead_ratio DESC;

-- 手动VACUUM
VACUUM ANALYZE employee_basic;

-- 调整自动VACUUM参数
ALTER TABLE employee_basic SET (autovacuum_vacuum_scale_factor = 0.05);
```

### 4. 查询慢

**问题**：查询耗时>1秒

**排查步骤**：
```sql
-- 1. 查看查询计划
EXPLAIN ANALYZE SELECT ...;

-- 2. 检查是否使用索引
-- 3. 检查是否全表扫描（Seq Scan）
-- 4. 检查统计信息是否过期
ANALYZE employee_basic;

-- 5. 考虑添加索引
CREATE INDEX idx_xxx ON table_name(column_name);
```

---

**最后更新**：2026年10月17日  
**维护人**：数据库管理员  
**参考文档**：
- [PostgreSQL官方文档](https://www.postgresql.org/docs/16/)
- [PostgreSQL性能优化](https://wiki.postgresql.org/wiki/Performance_Optimization)
- [阿里云RDS PostgreSQL最佳实践](https://help.aliyun.com/document_detail/96749.html)
