# PostgreSQL 分表策略设计

**针对场景**：劳务中介管理系统 - 激进多租户场景  
**PostgreSQL版本**：16+  
**最后更新**：2026年10月17日

---

## 📋 目录

1. [为什么需要分表](#为什么需要分表)
2. [分表策略选择](#分表策略选择)
3. [实施方案](#实施方案)
4. [分表维护](#分表维护)
5. [查询优化](#查询优化)
6. [监控告警](#监控告警)

---

## 🎯 为什么需要分表

### 业务背景

**当前规模**：
- 租户数：10-50家劳务中介公司
- 单租户员工数：500-2000人
- 单表数据量：<100万条

**未来激进多租户场景**（1-2年内）：
- **租户数增长**：预计100-500家
- **单租户员工数**：1000-5000人
- **总数据量**：预计达到1000万-5000万条

**性能瓶颈预测**：
- 单表数据量>1000万条，查询性能下降
- B-Tree索引深度增加，查询效率降低
- VACUUM和ANALYZE耗时增加
- 备份和恢复时间过长

### 分表带来的好处

✅ **查询性能提升**：
- 分区裁剪（Partition Pruning）自动过滤无关分区
- 索引更小，查询更快
- 并行扫描多个分区

✅ **维护效率提升**：
- VACUUM只需处理单个分区
- 删除历史数据只需DROP分区（毫秒级）
- 重建索引更快

✅ **扩展性提升**：
- 支持按时间/租户无限扩展
- 支持将老分区迁移到廉价存储

---

## 🎯 分表策略选择

### 方案对比

| 分表方式 | 优点 | 缺点 | 适用场景 |
|---------|------|------|---------|
| **按tenant_id分区** | 租户数据完全隔离<br>删除租户数据快 | 租户数据不均衡时效果差<br>需要预创建分区 | 租户数据量差异大<br>需要物理隔离 |
| **按时间分区** | 历史数据清理简单<br>数据均衡 | 跨时间查询性能差 | 时序数据（考勤、工资） |
| **按tenant_id + 时间分区** | 结合两者优点 | 分区数量多<br>管理复杂 | 大规模多租户 + 时序数据 |
| **Hash分区** | 数据均衡<br>自动分布 | 无法按业务逻辑清理数据 | 数据量大且均匀分布 |

### 推荐方案：混合分区策略

**核心表分区策略**：

| 表名 | 分区策略 | 原因 |
|------|---------|------|
| **employee_basic**（员工基本信息） | 按tenant_id List分区 | 租户隔离，查询都带tenant_id |
| **attendance**（考勤记录） | 按月份Range分区 | 典型时序数据，历史数据需要归档 |
| **salary_record**（工资记录） | 按月份Range分区 | 按月计算工资，按月查询 |
| **work_hour**（工时记录） | 按月份Range分区 | 时序数据，按月统计 |
| **advance_payment**（借支记录） | 不分区 | 数据量小（<10万条） |
| **rebate_record**（返费记录） | 不分区 | 数据量小（<5万条） |

---

## 🛠️ 实施方案

### 方案1：employee_basic按tenant_id分区

#### 1.1 分区表设计

```sql
-- 创建分区表（List分区）
CREATE TABLE employee_basic (
  id BIGSERIAL,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL,
  id_card VARCHAR(18) NOT NULL,
  phone VARCHAR(11) NOT NULL,
  status VARCHAR(20) NOT NULL,
  project_id BIGINT,
  
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  deleted_at TIMESTAMPTZ,
  
  PRIMARY KEY (id, tenant_id)  -- 主键必须包含分区键
) PARTITION BY LIST (tenant_id);

-- 创建默认分区（兜底）
CREATE TABLE employee_basic_default 
  PARTITION OF employee_basic DEFAULT;

-- 为每个租户创建分区
CREATE TABLE employee_basic_tenant_1001 
  PARTITION OF employee_basic
  FOR VALUES IN (1001);

CREATE TABLE employee_basic_tenant_1002 
  PARTITION OF employee_basic
  FOR VALUES IN (1002);

-- ... 更多租户分区
```

#### 1.2 分区索引

```sql
-- 在分区表上创建索引，自动应用到所有分区
CREATE INDEX idx_employee_status ON employee_basic(tenant_id, status);
CREATE INDEX idx_employee_phone ON employee_basic(tenant_id, phone);
CREATE INDEX idx_employee_id_card ON employee_basic(tenant_id, id_card);
CREATE INDEX idx_employee_created_at ON employee_basic(tenant_id, created_at DESC);
CREATE INDEX idx_employee_deleted_at ON employee_basic(tenant_id, deleted_at) 
  WHERE deleted_at IS NOT NULL;
```

#### 1.3 自动创建新租户分区

```sql
-- 创建函数：新租户注册时自动创建分区
CREATE OR REPLACE FUNCTION create_employee_partition_for_tenant()
RETURNS TRIGGER AS $$
DECLARE
  partition_name TEXT;
BEGIN
  partition_name := 'employee_basic_tenant_' || NEW.id;
  
  -- 检查分区是否已存在
  IF NOT EXISTS (
    SELECT 1 FROM pg_class WHERE relname = partition_name
  ) THEN
    -- 创建新分区
    EXECUTE format('
      CREATE TABLE %I PARTITION OF employee_basic
      FOR VALUES IN (%s)
    ', partition_name, NEW.id);
    
    RAISE NOTICE 'Created partition: %', partition_name;
  END IF;
  
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 在tenant表上创建触发器
CREATE TRIGGER create_employee_partition
  AFTER INSERT ON tenant
  FOR EACH ROW
  EXECUTE FUNCTION create_employee_partition_for_tenant();
```

#### 1.4 查询优化

```sql
-- ✅ 查询自动使用分区裁剪
SELECT * FROM employee_basic 
WHERE tenant_id = 1001 AND status = 'working';
-- 只扫描 employee_basic_tenant_1001 分区

-- 查看执行计划
EXPLAIN ANALYZE
SELECT * FROM employee_basic WHERE tenant_id = 1001;
-- 查找：Seq Scan on employee_basic_tenant_1001
```

---

### 方案2：考勤表按月份分区

#### 2.1 分区表设计

```sql
-- 创建分区表（Range分区）
CREATE TABLE attendance (
  id BIGSERIAL,
  tenant_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  attendance_date DATE NOT NULL,
  clock_in_time TIMESTAMPTZ,
  clock_out_time TIMESTAMPTZ,
  work_hours NUMERIC(4,2),
  status VARCHAR(20),
  
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  
  PRIMARY KEY (id, attendance_date)  -- 主键必须包含分区键
) PARTITION BY RANGE (attendance_date);

-- 创建索引
CREATE INDEX idx_attendance_tenant ON attendance(tenant_id, attendance_date DESC);
CREATE INDEX idx_attendance_employee ON attendance(employee_id, attendance_date DESC);

-- 创建2024年10月分区
CREATE TABLE attendance_2024_10 
  PARTITION OF attendance
  FOR VALUES FROM ('2024-10-01') TO ('2024-11-01');

-- 创建2024年11月分区
CREATE TABLE attendance_2024_11 
  PARTITION OF attendance
  FOR VALUES FROM ('2024-11-01') TO ('2024-12-01');

-- 创建2024年12月分区
CREATE TABLE attendance_2024_12 
  PARTITION OF attendance
  FOR VALUES FROM ('2024-12-01') TO ('2025-01-01');
```

#### 2.2 自动创建未来分区

```sql
-- 创建函数：提前创建未来3个月的分区
CREATE OR REPLACE FUNCTION create_future_attendance_partitions()
RETURNS void AS $$
DECLARE
  start_date DATE;
  end_date DATE;
  partition_name TEXT;
  i INT;
BEGIN
  -- 从下个月开始创建3个月的分区
  FOR i IN 1..3 LOOP
    start_date := DATE_TRUNC('month', CURRENT_DATE + (i || ' months')::INTERVAL);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'attendance_' || TO_CHAR(start_date, 'YYYY_MM');
    
    -- 检查分区是否已存在
    IF NOT EXISTS (
      SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
      -- 创建新分区
      EXECUTE format('
        CREATE TABLE %I PARTITION OF attendance
        FOR VALUES FROM (%L) TO (%L)
      ', partition_name, start_date, end_date);
      
      RAISE NOTICE 'Created partition: % for range [%, %)', 
        partition_name, start_date, end_date;
    END IF;
  END LOOP;
END;
$$ LANGUAGE plpgsql;

-- 定时任务：每天凌晨执行一次
-- 使用pg_cron扩展
CREATE EXTENSION pg_cron;

SELECT cron.schedule(
  'create-attendance-partitions',
  '0 0 * * *',  -- 每天凌晨0点
  $$SELECT create_future_attendance_partitions()$$
);
```

#### 2.3 查询优化

```sql
-- ✅ 查询自动使用分区裁剪
SELECT * FROM attendance 
WHERE tenant_id = 1001 
  AND attendance_date BETWEEN '2024-10-01' AND '2024-10-31';
-- 只扫描 attendance_2024_10 分区

-- ✅ 月度统计（单个分区）
SELECT employee_id, SUM(work_hours) as total_hours
FROM attendance
WHERE tenant_id = 1001 
  AND attendance_date BETWEEN '2024-10-01' AND '2024-10-31'
GROUP BY employee_id;
```

---

### 方案3：工资表按月份分区

#### 3.1 分区表设计

```sql
-- 创建分区表（Range分区）
CREATE TABLE salary_record (
  id BIGSERIAL,
  tenant_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  salary_month DATE NOT NULL,  -- 工资月份（2024-10-01）
  
  gross_salary NUMERIC(10,2) NOT NULL,  -- 应发工资
  net_salary NUMERIC(10,2) NOT NULL,    -- 实发工资
  base_salary NUMERIC(10,2),            -- 底薪
  overtime_pay NUMERIC(10,2),           -- 加班工资
  deduction NUMERIC(10,2),              -- 扣款
  
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
  
  PRIMARY KEY (id, salary_month),
  
  CONSTRAINT check_salary_positive 
    CHECK (gross_salary >= 0 AND net_salary >= 0 AND net_salary <= gross_salary)
) PARTITION BY RANGE (salary_month);

-- 创建索引
CREATE INDEX idx_salary_tenant ON salary_record(tenant_id, salary_month DESC);
CREATE INDEX idx_salary_employee ON salary_record(employee_id, salary_month DESC);

-- 创建2024年分区
CREATE TABLE salary_record_2024_10 
  PARTITION OF salary_record
  FOR VALUES FROM ('2024-10-01') TO ('2024-11-01');

CREATE TABLE salary_record_2024_11 
  PARTITION OF salary_record
  FOR VALUES FROM ('2024-11-01') TO ('2024-12-01');

CREATE TABLE salary_record_2024_12 
  PARTITION OF salary_record
  FOR VALUES FROM ('2024-12-01') TO ('2025-01-01');
```

#### 3.2 自动创建未来分区（同考勤表）

```sql
-- 创建函数（复用考勤表逻辑）
CREATE OR REPLACE FUNCTION create_future_salary_partitions()
RETURNS void AS $$
DECLARE
  start_date DATE;
  end_date DATE;
  partition_name TEXT;
  i INT;
BEGIN
  FOR i IN 1..3 LOOP
    start_date := DATE_TRUNC('month', CURRENT_DATE + (i || ' months')::INTERVAL);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'salary_record_' || TO_CHAR(start_date, 'YYYY_MM');
    
    IF NOT EXISTS (
      SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
      EXECUTE format('
        CREATE TABLE %I PARTITION OF salary_record
        FOR VALUES FROM (%L) TO (%L)
      ', partition_name, start_date, end_date);
      
      RAISE NOTICE 'Created partition: % for range [%, %)', 
        partition_name, start_date, end_date;
    END IF;
  END LOOP;
END;
$$ LANGUAGE plpgsql;

-- 定时任务
SELECT cron.schedule(
  'create-salary-partitions',
  '0 0 * * *',
  $$SELECT create_future_salary_partitions()$$
);
```

---

## 🔧 分表维护

### 1. 删除历史分区（归档）

```sql
-- 删除2年前的考勤数据
DROP TABLE IF EXISTS attendance_2022_10;
DROP TABLE IF EXISTS attendance_2022_11;
DROP TABLE IF EXISTS attendance_2022_12;

-- 优势：毫秒级删除，不影响其他分区

-- 归档后再删除（可选）
-- 1. 导出分区数据
pg_dump -t attendance_2022_10 labor_intermediaries > attendance_2022_10.sql

-- 2. 压缩归档
gzip attendance_2022_10.sql

-- 3. 删除分区
DROP TABLE attendance_2022_10;
```

### 2. DETACH分区（临时迁移）

```sql
-- 将分区从分区表中分离（不删除数据）
ALTER TABLE attendance DETACH PARTITION attendance_2022_10;

-- 此时 attendance_2022_10 变成独立表，可以：
-- - 导出到其他数据库
-- - 迁移到廉价存储
-- - 单独备份

-- 重新附加分区
ALTER TABLE attendance ATTACH PARTITION attendance_2022_10
  FOR VALUES FROM ('2022-10-01') TO ('2022-11-01');
```

### 3. VACUUM分区

```sql
-- VACUUM单个分区（比VACUUM整表快）
VACUUM ANALYZE attendance_2024_10;

-- 查看分区膨胀情况
SELECT schemaname, tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
  n_dead_tup
FROM pg_stat_user_tables
WHERE tablename LIKE 'attendance_2024_%'
ORDER BY n_dead_tup DESC;
```

### 4. REINDEX分区

```sql
-- 重建单个分区的索引（CONCURRENTLY不阻塞读写）
REINDEX TABLE CONCURRENTLY attendance_2024_10;

-- 重建所有分区的索引
DO $$
DECLARE
  partition_name TEXT;
BEGIN
  FOR partition_name IN 
    SELECT tablename FROM pg_tables 
    WHERE tablename LIKE 'attendance_2024_%'
  LOOP
    EXECUTE 'REINDEX TABLE CONCURRENTLY ' || partition_name;
  END LOOP;
END $$;
```

---

## 🚀 查询优化

### 1. 分区裁剪（Partition Pruning）

```sql
-- ✅ 查询自动裁剪分区
EXPLAIN ANALYZE
SELECT * FROM attendance 
WHERE attendance_date = '2024-10-15';

-- 执行计划显示：
-- Seq Scan on attendance_2024_10  (只扫描1个分区)

-- ❌ 查询无法裁剪分区（扫描所有分区）
EXPLAIN ANALYZE
SELECT * FROM attendance 
WHERE EXTRACT(MONTH FROM attendance_date) = 10;

-- 应该改为：
WHERE attendance_date >= '2024-10-01' AND attendance_date < '2024-11-01'
```

### 2. 分区连接（Partition-wise Join）

```sql
-- PostgreSQL 11+支持分区表JOIN优化

-- ✅ 两个分区表JOIN
SELECT a.*, e.name
FROM attendance a
JOIN employee_basic e ON a.employee_id = e.id AND a.tenant_id = e.tenant_id
WHERE a.attendance_date BETWEEN '2024-10-01' AND '2024-10-31'
  AND a.tenant_id = 1001;

-- 优化器会分别JOIN对应的分区：
-- attendance_2024_10 JOIN employee_basic_tenant_1001
```

### 3. 并行扫描分区

```sql
-- 启用并行扫描
SET max_parallel_workers_per_gather = 4;

-- 查询自动并行扫描多个分区
EXPLAIN ANALYZE
SELECT COUNT(*) FROM attendance
WHERE attendance_date >= '2024-01-01' AND attendance_date < '2024-12-31';

-- 执行计划显示：
-- Parallel Append (4个worker并行扫描12个分区)
```

---

## 📊 监控告警

### 1. 分区数量监控

```sql
-- 查看分区数量
SELECT 
  nmsp_parent.nspname AS parent_schema,
  parent.relname AS parent_table,
  COUNT(*) AS partition_count
FROM pg_inherits
JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
JOIN pg_class child ON pg_inherits.inhrelid = child.oid
JOIN pg_namespace nmsp_parent ON parent.relnamespace = nmsp_parent.oid
GROUP BY nmsp_parent.nspname, parent.relname
ORDER BY partition_count DESC;

-- 告警：分区数量>100
```

### 2. 分区大小监控

```sql
-- 查看各分区大小
SELECT 
  schemaname, 
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE tablename LIKE 'attendance_2024_%'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- 告警：单个分区>10GB
```

### 3. 未来分区检查

```sql
-- 检查是否已创建未来3个月的分区
SELECT 
  TO_CHAR(CURRENT_DATE + (i || ' months')::INTERVAL, 'YYYY_MM') AS expected_partition,
  EXISTS (
    SELECT 1 FROM pg_class 
    WHERE relname = 'attendance_' || TO_CHAR(CURRENT_DATE + (i || ' months')::INTERVAL, 'YYYY_MM')
  ) AS partition_exists
FROM generate_series(1, 3) AS i;

-- 告警：未来分区不存在
```

---

## 📝 迁移步骤

### 从非分区表迁移到分区表

```sql
-- 1. 创建新的分区表
CREATE TABLE attendance_new (
  ...
) PARTITION BY RANGE (attendance_date);

-- 2. 创建所有分区
CREATE TABLE attendance_new_2024_01 ...
CREATE TABLE attendance_new_2024_02 ...
-- ...

-- 3. 迁移数据（分批迁移，避免长时间锁表）
INSERT INTO attendance_new 
SELECT * FROM attendance 
WHERE attendance_date BETWEEN '2024-01-01' AND '2024-01-31';

INSERT INTO attendance_new 
SELECT * FROM attendance 
WHERE attendance_date BETWEEN '2024-02-01' AND '2024-02-29';
-- ...

-- 4. 验证数据
SELECT COUNT(*) FROM attendance;
SELECT COUNT(*) FROM attendance_new;

-- 5. 切换表名（需要停机，或使用触发器同步）
BEGIN;
ALTER TABLE attendance RENAME TO attendance_old;
ALTER TABLE attendance_new RENAME TO attendance;
COMMIT;

-- 6. 删除旧表
DROP TABLE attendance_old;
```

---

## 🎯 总结

### 分区策略总结

| 表名 | 分区策略 | 分区键 | 预计分区数 |
|------|---------|--------|-----------|
| **employee_basic** | List分区（按租户） | tenant_id | 100-500个 |
| **attendance** | Range分区（按月） | attendance_date | 36个（保留3年） |
| **salary_record** | Range分区（按月） | salary_month | 36个（保留3年） |
| **work_hour** | Range分区（按月） | work_date | 36个（保留3年） |

### 关键注意事项

✅ **主键必须包含分区键**：
```sql
PRIMARY KEY (id, tenant_id)  -- List分区
PRIMARY KEY (id, attendance_date)  -- Range分区
```

✅ **唯一约束必须包含分区键**：
```sql
UNIQUE (tenant_id, id_card)  -- ✅ 正确
UNIQUE (id_card)  -- ❌ 错误（缺少tenant_id）
```

✅ **外键约束限制**：
- 分区表不能作为外键引用的目标
- 解决方案：触发器实现外键约束

✅ **查询必须包含分区键**：
```sql
WHERE tenant_id = 1001  -- ✅ 触发分区裁剪
WHERE name = '张三'     -- ❌ 全表扫描
```

---

**最后更新**：2026年10月17日  
**维护人**：数据库架构师  
**参考文档**：
- [PostgreSQL分区表官方文档](https://www.postgresql.org/docs/16/ddl-partitioning.html)
- [PostgreSQL分区表最佳实践](https://wiki.postgresql.org/wiki/Table_partitioning)
