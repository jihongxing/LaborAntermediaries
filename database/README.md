# 数据库

劳务中介管理系统 - 数据库相关文件

## 📋 目录说明

```
database/
├── schema/                # 数据库表结构（DDL）
│   ├── V1.0.0__init.sql              # 初始化脚本
│   ├── V1.1.0__add_advance_table.sql # 增量脚本示例
│   └── ...
├── data/                  # 初始化数据（DML）
│   ├── dict_data.sql     # 字典数据
│   └── test_data.sql     # 测试数据
├── docs/                  # 数据库文档
│   ├── ER图.png          # 实体关系图
│   └── 表结构说明.md     # 表结构详细说明
└── README.md              # 本文件
```

## 🗄️ 数据库设计

### 技术选型

- **数据库**：MySQL 8.0+
- **字符集**：utf8mb4
- **排序规则**：utf8mb4_unicode_ci
- **引擎**：InnoDB

### 核心设计原则

#### 1. 多租户设计（强制）

所有业务表必须包含 `tenant_id` 字段：

```sql
CREATE TABLE xxx (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  tenant_id BIGINT NOT NULL COMMENT '租户ID（必须）',
  ...其他字段...
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted_at TIMESTAMP NULL COMMENT '删除时间（软删除）',
  INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='表说明';
```

#### 2. 必备字段（强制）

所有表必须包含：
- `id`：主键，BIGINT类型
- `tenant_id`：租户ID（业务表必须）
- `created_at`：创建时间
- `updated_at`：更新时间
- `deleted_at`：删除时间（软删除）

#### 3. 命名规范（强制）

| 对象 | 规范 | 示例 |
|------|------|------|
| 表名 | 小写+下划线 | `employee_basic` |
| 字段名 | 小写+下划线 | `created_at` |
| 索引名 | `idx_字段名` | `idx_tenant_id` |
| 唯一索引 | `uk_字段名` | `uk_id_card` |

#### 4. 字段类型约束（强制）

| 数据类型 | MySQL类型 | 说明 |
|---------|-----------|------|
| 主键 | `BIGINT` | 统一用BIGINT |
| 金额 | `DECIMAL(10,2)` | **必须用DECIMAL，禁止用DOUBLE** |
| 日期时间 | `TIMESTAMP` | 不用DATETIME |
| 布尔 | `TINYINT(1)` | 0=false, 1=true |
| 字符串 | `VARCHAR(N)` | 根据实际长度 |
| 长文本 | `TEXT` | 超过500字符 |

## 📐 数据库结构概览

### 核心表（约80张）

#### 用户与权限（5张表）
- `sys_user` - 用户表
- `sys_role` - 角色表
- `sys_permission` - 权限表
- `sys_user_role` - 用户角色关联
- `sys_role_permission` - 角色权限关联

#### 租户与基础数据（5张表）
- `sys_tenant` - 租户表
- `base_enterprise` - 企业表
- `base_project` - 项目表
- `base_position` - 岗位表
- `base_channel` - 渠道表

#### 招聘与员工（10张表）
- `recruit_job_posting` - 岗位发布
- `recruit_applicant` - 求职者报名
- `employee_basic` - 员工基本信息
- ...

#### 考勤与工时（5张表）
- `attendance_record` - 考勤记录
- `workhour_summary` - 工时汇总
- ...

#### 工资管理（8张表）
- `salary_policy` - 工资政策
- `salary_calculation` - 工资计算
- `salary_detail` - 工资明细
- ...

#### 借支管理（5张表）
- `advance_application` - 借支申请
- `advance_approval` - 借支审批
- ...

#### 返费结算（6张表）
- `rebate_policy` - 返费政策
- `rebate_calculation` - 返费计算
- ...

详细表结构请参考：[表结构说明文档](./docs/表结构说明.md)

## 🚀 数据库初始化

### 创建数据库

```sql
CREATE DATABASE labor_intermediaries 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci;
```

### 执行初始化脚本

```bash
# 执行表结构初始化
mysql -u root -p labor_intermediaries < schema/V1.0.0__init.sql

# 执行字典数据初始化
mysql -u root -p labor_intermediaries < data/dict_data.sql

# 执行测试数据（可选）
mysql -u root -p labor_intermediaries < data/test_data.sql
```

## 📝 数据库变更管理

### 使用Flyway进行版本管理

脚本命名规范：
```
V{版本号}__{描述}.sql

示例：
V1.0.0__init.sql
V1.1.0__add_advance_table.sql
V1.1.1__fix_salary_precision.sql
```

### 变更原则

1. ✅ 只能新增，不能修改已执行的脚本
2. ✅ 每个脚本必须可独立执行
3. ✅ 必须提供回滚脚本
4. ✅ 生产环境变更必须在凌晨执行

### 变更示例

**增量脚本**（schema/V1.1.0__add_advance_table.sql）：
```sql
-- 新增借支申请表
CREATE TABLE advance_application (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL,
  INDEX idx_tenant_id (tenant_id),
  INDEX idx_employee_id (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='借支申请表';
```

**回滚脚本**（schema/R1.1.0__rollback_advance.sql）：
```sql
-- 回滚：删除借支申请表
DROP TABLE IF EXISTS advance_application;
```

## 🔐 安全规范

### 数据安全

1. ✅ 敏感数据必须加密存储（密码、身份证、银行卡）
2. ✅ 禁止在生产环境直接执行DELETE（必须软删除）
3. ✅ 禁止在生产环境直接执行DROP TABLE
4. ✅ 每日自动备份数据库

### 访问控制

```sql
-- 应用账号（只读权限）
CREATE USER 'labor_app_read'@'%' IDENTIFIED BY 'password';
GRANT SELECT ON labor_intermediaries.* TO 'labor_app_read'@'%';

-- 应用账号（读写权限）
CREATE USER 'labor_app_write'@'%' IDENTIFIED BY 'password';
GRANT SELECT, INSERT, UPDATE ON labor_intermediaries.* TO 'labor_app_write'@'%';

-- 禁止DELETE和DROP权限
```

## 📊 性能优化

### 索引规范

**必须加索引的字段**：
1. `tenant_id`（所有业务表）
2. `created_at`（时间范围查询）
3. `status`（状态查询）
4. 外键字段
5. 频繁作为WHERE条件的字段

### 慢查询优化

```sql
-- 开启慢查询日志
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;

-- 查看慢查询
SELECT * FROM mysql.slow_log ORDER BY query_time DESC LIMIT 10;
```

## 🔧 维护工具

### 备份脚本

```bash
# 每日备份
mysqldump -u root -p labor_intermediaries \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  | gzip > backup/labor_$(date +%Y%m%d).sql.gz
```

### 数据清理

```bash
# 清理超过90天的软删除数据
DELETE FROM employee_basic 
WHERE deleted_at IS NOT NULL 
  AND deleted_at < DATE_SUB(NOW(), INTERVAL 90 DAY);
```

## 📚 相关文档

- [数据库设计文档](./docs/表结构说明.md)
- [ER图](./docs/ER图.png)
- [技术规范](../CLAUDE.md)
- [工程治理](../GOVERNANCE.md)

## 🤝 贡献

数据库变更请遵循以下流程：
1. 创建变更脚本（遵循命名规范）
2. 创建回滚脚本
3. 在测试环境验证
4. 提交Pull Request
5. 经DBA审核通过后合并

详细请参考 [贡献指南](../CONTRIBUTING.md)
