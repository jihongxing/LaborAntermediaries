# PostgreSQL 主从架构设计

**针对场景**：劳务中介管理系统 - 高可用生产环境  
**PostgreSQL版本**：16+  
**最后更新**：2026年10月17日

---

## 📋 目录

1. [架构概述](#架构概述)
2. [主从流复制配置](#主从流复制配置)
3. [读写分离实现](#读写分离实现)
4. [自动故障转移](#自动故障转移)
5. [监控与告警](#监控与告警)
6. [运维手册](#运维手册)
7. [常见问题](#常见问题)

---

## 🏗️ 架构概述

### 架构图

```
                    ┌──────────────┐
                    │ 应用服务器   │
                    └──────┬───────┘
                           │
                  ┌────────┴─────────┐
                  │                  │
             写操作 │                  │ 读操作
                  │                  │
           ┌──────▼──────┐    ┌─────▼──────┐
           │  Primary    │───>│  Standby   │
           │  (主库)     │    │  (从库)    │
           └─────────────┘    └────────────┘
                WAL流式复制
                (Streaming Replication)

           配置：             配置：
           - 读写             - 只读
           - WAL归档          - 自动应用WAL
           - 同步/异步复制     - 热备
```

### 关键特性

| 特性 | 说明 | 优势 |
|------|------|------|
| **流式复制** | 通过WAL日志流实时同步 | 延迟<10ms |
| **热备份** | Standby支持只读查询 | 读写分离，提升性能 |
| **自动故障转移** | Standby自动晋升为Primary | 高可用，RTO<30s |
| **同步复制** | 事务提交等待Standby确认 | 零数据丢失（RPO=0） |
| **异步复制** | 事务提交不等待Standby | 更高性能，略有数据丢失风险 |

### 部署规格

**测试环境**：
- 1台Primary：4核8GB
- 1台Standby：4核8GB
- 同步模式：异步
- 预期延迟：<100ms

**生产环境**：
- 1台Primary：8核16GB，SSD 500GB
- 1台Standby：8核16GB，SSD 500GB
- 同步模式：同步（金融级数据一致性）
- 预期延迟：<10ms
- 网络：万兆内网

---

## 🔧 主从流复制配置

### 1. Primary配置

#### 1.1 创建复制用户

```sql
-- 在Primary上执行
CREATE USER replicator WITH REPLICATION ENCRYPTED PASSWORD 'your_secure_password';
```

#### 1.2 配置postgresql.conf

```ini
# /var/lib/postgresql/data/postgresql.conf

# ==================== 复制配置 ====================
wal_level = replica                    # 启用复制
max_wal_senders = 10                   # 最大WAL发送进程数
max_replication_slots = 10             # 最大复制槽数
wal_keep_size = 1GB                    # 保留WAL大小

# ==================== 同步复制 ====================
synchronous_commit = on                # 同步提交（生产环境）
# synchronous_commit = off             # 异步提交（测试环境，性能更好）
synchronous_standby_names = 'standby1' # 同步Standby名称

# ==================== WAL归档 ====================
archive_mode = on
archive_command = 'cp %p /backup/wal_archive/%f'  # 归档命令

# ==================== 监听地址 ====================
listen_addresses = '*'                 # 监听所有地址
port = 5432
```

#### 1.3 配置pg_hba.conf

```ini
# /var/lib/postgresql/data/pg_hba.conf

# TYPE  DATABASE        USER            ADDRESS                 METHOD

# 允许replicator从Standby连接
host    replication     replicator      standby_ip/32           scram-sha-256

# 示例
host    replication     replicator      192.168.1.101/32        scram-sha-256
```

#### 1.4 重启Primary

```bash
sudo systemctl restart postgresql
```

#### 1.5 创建复制槽（推荐）

```sql
-- 在Primary上执行
SELECT * FROM pg_create_physical_replication_slot('standby1_slot');

-- 查看复制槽
SELECT * FROM pg_replication_slots;
```

---

### 2. Standby配置

#### 2.1 停止Standby（如果已启动）

```bash
sudo systemctl stop postgresql
```

#### 2.2 清空数据目录

```bash
# 备份旧数据（可选）
sudo mv /var/lib/postgresql/data /var/lib/postgresql/data.bak

# 创建新数据目录
sudo mkdir -p /var/lib/postgresql/data
sudo chown postgres:postgres /var/lib/postgresql/data
```

#### 2.3 从Primary同步基础备份

```bash
# 使用pg_basebackup从Primary同步数据
sudo -u postgres pg_basebackup \
  -h primary_ip \
  -U replicator \
  -D /var/lib/postgresql/data \
  -P \
  -X stream \
  -R

# 参数说明：
# -h: Primary的IP地址
# -U: 复制用户
# -D: Standby数据目录
# -P: 显示进度
# -X stream: 流式传输WAL
# -R: 自动创建standby.signal和配置文件

# 示例
sudo -u postgres pg_basebackup \
  -h 192.168.1.100 \
  -U replicator \
  -D /var/lib/postgresql/data \
  -P \
  -X stream \
  -R
```

#### 2.4 配置Standby参数

```ini
# /var/lib/postgresql/data/postgresql.conf

# ==================== Standby配置 ====================
hot_standby = on                       # 启用热备（支持只读查询）
max_standby_streaming_delay = 30s      # Standby最大延迟
wal_receiver_status_interval = 10s     # 状态报告间隔
hot_standby_feedback = on              # 反馈给Primary，避免冲突

# ==================== 连接Primary ====================
primary_conninfo = 'host=primary_ip port=5432 user=replicator password=your_password application_name=standby1'
primary_slot_name = 'standby1_slot'    # 使用复制槽

# 示例
primary_conninfo = 'host=192.168.1.100 port=5432 user=replicator password=your_password application_name=standby1'
```

#### 2.5 启动Standby

```bash
sudo systemctl start postgresql
```

#### 2.6 验证复制状态

**在Primary上查询**：
```sql
-- 查看复制状态
SELECT * FROM pg_stat_replication;

-- 关键字段：
-- application_name: standby1
-- state: streaming
-- sync_state: sync（同步） 或 async（异步）
-- replay_lag: 复制延迟

-- 查看复制槽
SELECT * FROM pg_replication_slots;
```

**在Standby上查询**：
```sql
-- 查看恢复状态
SELECT * FROM pg_stat_wal_receiver;

-- 检查是否是Standby
SELECT pg_is_in_recovery();  -- 返回 true 表示是Standby
```

---

## 📊 读写分离实现

### 方案1：应用层读写分离（推荐）

#### Spring Boot配置

```yaml
# application.yml
spring:
  datasource:
    # 主库（写）
    primary:
      jdbc-url: jdbc:postgresql://primary_ip:5432/labor_intermediaries
      username: postgres
      password: ${PRIMARY_DB_PASSWORD}
      hikari:
        maximum-pool-size: 20
        minimum-idle: 5
    
    # 从库（读）
    standby:
      jdbc-url: jdbc:postgresql://standby_ip:5432/labor_intermediaries
      username: postgres
      password: ${STANDBY_DB_PASSWORD}
      hikari:
        maximum-pool-size: 40  # 读库连接数更多
        minimum-idle: 10
        read-only: true        # 强制只读
```

#### Java配置类

```java
@Configuration
public class DataSourceConfig {
    
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.primary")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().build();
    }
    
    @Bean
    @ConfigurationProperties("spring.datasource.standby")
    public DataSource standbyDataSource() {
        return DataSourceBuilder.create().build();
    }
    
    @Bean
    public DataSource routingDataSource(
        @Qualifier("primaryDataSource") DataSource primary,
        @Qualifier("standbyDataSource") DataSource standby
    ) {
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceType.PRIMARY, primary);
        targetDataSources.put(DataSourceType.STANDBY, standby);
        
        RoutingDataSource routing = new RoutingDataSource();
        routing.setTargetDataSources(targetDataSources);
        routing.setDefaultTargetDataSource(primary);
        return routing;
    }
}
```

#### 动态路由数据源

```java
public class RoutingDataSource extends AbstractRoutingDataSource {
    
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getDataSourceType();
    }
}

public class DataSourceContextHolder {
    private static final ThreadLocal<DataSourceType> contextHolder = new ThreadLocal<>();
    
    public static void setDataSourceType(DataSourceType type) {
        contextHolder.set(type);
    }
    
    public static DataSourceType getDataSourceType() {
        return contextHolder.get() != null ? contextHolder.get() : DataSourceType.PRIMARY;
    }
    
    public static void clear() {
        contextHolder.remove();
    }
}

public enum DataSourceType {
    PRIMARY,   // 主库（写）
    STANDBY    // 从库（读）
}
```

#### 注解驱动

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnly {
}

@Aspect
@Component
public class DataSourceAspect {
    
    @Around("@annotation(readOnly)")
    public Object setReadDataSource(ProceedingJoinPoint joinPoint, ReadOnly readOnly) throws Throwable {
        try {
            DataSourceContextHolder.setDataSourceType(DataSourceType.STANDBY);
            return joinPoint.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
```

#### 使用示例

```java
@Service
public class EmployeeService {
    
    // 写操作 - 自动路由到Primary
    @Transactional
    public void save(Employee employee) {
        employeeMapper.insert(employee);
    }
    
    // 读操作 - 手动路由到Standby
    @ReadOnly
    public List<Employee> list(Long tenantId) {
        return employeeMapper.selectByTenantId(tenantId);
    }
    
    // 读操作 - 自动路由到Standby（@Transactional(readOnly = true)）
    @Transactional(readOnly = true)
    public Employee getById(Long id) {
        return employeeMapper.selectById(id);
    }
}
```

---

### 方案2：PgPool-II中间件（可选）

```
       ┌──────────────┐
       │ 应用服务器   │
       └──────┬───────┘
              │
       ┌──────▼───────┐
       │  PgPool-II   │  (连接池 + 负载均衡 + 读写分离)
       └──────┬───────┘
              │
       ┌──────┴───────┐
       │              │
   ┌───▼────┐    ┌───▼────┐
   │Primary │    │Standby │
   └────────┘    └────────┘
```

**优点**：
- 应用无感知
- 自动负载均衡
- 连接池复用

**缺点**：
- 增加运维复杂度
- 单点故障风险
- 略有性能损耗

---

## 🔄 自动故障转移

### 方案1：Patroni（推荐）

Patroni是PostgreSQL的高可用解决方案，基于etcd/Consul/Zookeeper实现。

#### 架构图

```
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │  etcd 1  │───>│  etcd 2  │───>│  etcd 3  │
    └────┬─────┘    └────┬─────┘    └────┬─────┘
         │               │               │
    ┌────┴───────────────┴───────────────┴────┐
    │             分布式共识层                  │
    └────┬───────────────┬───────────────┬────┘
         │               │               │
    ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
    │Patroni 1│    │Patroni 2│    │Patroni 3│
    │(Primary)│    │(Standby)│    │(Standby)│
    └────┬────┘    └────┬────┘    └────┬────┘
         │               │               │
    ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
    │  PG 1   │───>│  PG 2   │───>│  PG 3   │
    └─────────┘    └─────────┘    └─────────┘
```

#### 安装Patroni

```bash
# 安装依赖
sudo apt-get install python3-pip python3-dev libpq-dev

# 安装Patroni
sudo pip3 install patroni[etcd]

# 安装etcd
sudo apt-get install etcd
```

#### 配置Patroni

```yaml
# /etc/patroni/patroni.yml
scope: postgres-cluster
namespace: /db/
name: node1

restapi:
  listen: 0.0.0.0:8008
  connect_address: node1_ip:8008

etcd:
  host: etcd_ip:2379

bootstrap:
  dcs:
    ttl: 30
    loop_wait: 10
    retry_timeout: 10
    maximum_lag_on_failover: 1048576
    postgresql:
      use_pg_rewind: true
      parameters:
        max_connections: 200
        shared_buffers: 4GB
        wal_level: replica
        hot_standby: on
        
  initdb:
    - encoding: UTF8
    - data-checksums

  pg_hba:
    - host replication replicator 0.0.0.0/0 scram-sha-256
    - host all all 0.0.0.0/0 scram-sha-256

postgresql:
  listen: 0.0.0.0:5432
  connect_address: node1_ip:5432
  data_dir: /var/lib/postgresql/16/main
  bin_dir: /usr/lib/postgresql/16/bin
  authentication:
    replication:
      username: replicator
      password: your_password
    superuser:
      username: postgres
      password: your_password

watchdog:
  mode: automatic
```

#### 启动Patroni

```bash
# 创建systemd服务
sudo cat > /etc/systemd/system/patroni.service <<EOF
[Unit]
Description=Patroni PostgreSQL
After=syslog.target network.target

[Service]
Type=simple
User=postgres
Group=postgres
ExecStart=/usr/local/bin/patroni /etc/patroni/patroni.yml
KillMode=process
TimeoutSec=30
Restart=no

[Install]
WantedBy=multi-user.target
EOF

# 启动
sudo systemctl daemon-reload
sudo systemctl enable patroni
sudo systemctl start patroni

# 查看状态
sudo systemctl status patroni
```

#### 查看集群状态

```bash
# 使用patronictl
patronictl -c /etc/patroni/patroni.yml list

# 输出：
# + Cluster: postgres-cluster (1234567890)
# | Member | Host       | Role    | State   | TL | Lag in MB |
# +--------+------------+---------+---------+----+-----------+
# | node1  | 10.0.0.1   | Leader  | running | 1  |           |
# | node2  | 10.0.0.2   | Replica | running | 1  | 0         |
# +--------+------------+---------+---------+----+-----------+
```

#### 手动故障转移

```bash
# 切换Primary
patronictl -c /etc/patroni/patroni.yml switchover

# 重启集群
patronictl -c /etc/patroni/patroni.yml restart postgres-cluster
```

---

### 方案2：pg_auto_failover（备选）

pg_auto_failover是PostgreSQL官方推荐的故障转移工具。

#### 架构图

```
    ┌──────────────┐
    │   Monitor    │  (故障检测、决策)
    └──────┬───────┘
           │
    ┌──────┴───────┐
    │              │
┌───▼────┐    ┌───▼────┐
│Primary │───>│Standby │
└────────┘    └────────┘
```

#### 安装

```bash
# 安装pg_auto_failover
sudo apt-get install postgresql-16-auto-failover
```

#### 创建Monitor节点

```bash
# 初始化Monitor
pg_autoctl create monitor \
  --pgdata /var/lib/postgresql/monitor \
  --pgctl /usr/lib/postgresql/16/bin/pg_ctl

# 启动Monitor
pg_autoctl run --pgdata /var/lib/postgresql/monitor
```

#### 创建Primary节点

```bash
# 初始化Primary
pg_autoctl create postgres \
  --pgdata /var/lib/postgresql/16/main \
  --monitor postgres://autoctl_node@monitor_ip:5432/pg_auto_failover \
  --name node1

# 启动Primary
pg_autoctl run --pgdata /var/lib/postgresql/16/main
```

#### 创建Standby节点

```bash
# 初始化Standby
pg_autoctl create postgres \
  --pgdata /var/lib/postgresql/16/main \
  --monitor postgres://autoctl_node@monitor_ip:5432/pg_auto_failover \
  --name node2

# 启动Standby
pg_autoctl run --pgdata /var/lib/postgresql/16/main
```

---

## 📊 监控与告警

### 1. 复制延迟监控

```sql
-- 在Primary上查询复制延迟
SELECT 
  application_name,
  client_addr,
  state,
  sync_state,
  pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes,
  replay_lag
FROM pg_stat_replication;

-- 告警条件：
-- replay_lag > 10s（同步复制）
-- replay_lag > 60s（异步复制）
```

### 2. 复制槽监控

```sql
-- 在Primary上查询复制槽
SELECT 
  slot_name,
  slot_type,
  active,
  pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS retained_wal_bytes
FROM pg_replication_slots;

-- 告警条件：
-- active = false（复制槽未激活）
-- retained_wal_bytes > 10GB（WAL堆积过多）
```

### 3. Standby连接监控

```sql
-- 在Standby上查询连接状态
SELECT 
  status,
  receive_start_lsn,
  receive_start_tli,
  received_lsn,
  conninfo
FROM pg_stat_wal_receiver;

-- 告警条件：
-- status != 'streaming'（连接断开）
```

### 4. 数据库角色监控

```sql
-- 检查当前节点角色
SELECT pg_is_in_recovery();

-- true  = Standby
-- false = Primary

-- 告警：Primary数量 != 1（脑裂或无Primary）
```

### 5. Prometheus + Grafana监控

#### postgres_exporter配置

```bash
# 安装postgres_exporter
wget https://github.com/prometheus-community/postgres_exporter/releases/download/v0.15.0/postgres_exporter-0.15.0.linux-amd64.tar.gz
tar -xzf postgres_exporter-0.15.0.linux-amd64.tar.gz

# 创建监控用户
CREATE USER postgres_exporter WITH PASSWORD 'your_password';
GRANT pg_monitor TO postgres_exporter;

# 启动exporter
export DATA_SOURCE_NAME="postgresql://postgres_exporter:your_password@localhost:5432/postgres?sslmode=disable"
./postgres_exporter

# 指标端点：http://localhost:9187/metrics
```

#### Prometheus配置

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'postgresql-primary'
    static_configs:
      - targets: ['primary_ip:9187']
        labels:
          instance: 'primary'
          
  - job_name: 'postgresql-standby'
    static_configs:
      - targets: ['standby_ip:9187']
        labels:
          instance: 'standby'
```

#### Grafana仪表盘

使用官方仪表盘模板：
- [PostgreSQL Database Dashboard](https://grafana.com/grafana/dashboards/9628)
- [PostgreSQL Replication Dashboard](https://grafana.com/grafana/dashboards/12485)

---

## 🛠️ 运维手册

### 1. 计划内Primary切换

```bash
# 使用Patroni
patronictl -c /etc/patroni/patroni.yml switchover

# 手动切换
# 1. 停止Primary
pg_ctl stop -D /var/lib/postgresql/16/main

# 2. 晋升Standby为Primary
pg_ctl promote -D /var/lib/postgresql/16/main

# 3. 将旧Primary配置为新Standby（略）
```

### 2. Standby重建

```bash
# 场景：Standby损坏或延迟过大

# 1. 停止Standby
sudo systemctl stop postgresql

# 2. 清空数据目录
sudo rm -rf /var/lib/postgresql/16/main

# 3. 重新同步
sudo -u postgres pg_basebackup \
  -h primary_ip \
  -U replicator \
  -D /var/lib/postgresql/16/main \
  -P -X stream -R

# 4. 启动Standby
sudo systemctl start postgresql
```

### 3. 查看复制状态

```sql
-- 在Primary上
SELECT * FROM pg_stat_replication \gx

-- 在Standby上
SELECT * FROM pg_stat_wal_receiver \gx
```

### 4. 查看WAL延迟

```sql
-- 在Primary上
SELECT pg_current_wal_lsn();

-- 在Standby上
SELECT pg_last_wal_receive_lsn();  -- 接收到的最新WAL
SELECT pg_last_wal_replay_lsn();   -- 已应用的最新WAL

-- 计算延迟
SELECT pg_wal_lsn_diff(pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn());
```

---

## ❓ 常见问题

### 1. Standby无法连接Primary

**错误日志**：
```
FATAL: could not connect to the primary server: connection refused
```

**排查**：
```bash
# 1. 检查Primary是否启动
sudo systemctl status postgresql

# 2. 检查Primary是否监听外部地址
psql -U postgres -c "SHOW listen_addresses;"

# 3. 检查防火墙
sudo ufw status
sudo ufw allow 5432/tcp

# 4. 检查pg_hba.conf是否允许replicator连接
grep replicator /var/lib/postgresql/16/main/pg_hba.conf
```

### 2. 复制槽堆积WAL

**问题**：复制槽保留大量WAL，磁盘占用过高

**解决**：
```sql
-- 查看复制槽状态
SELECT * FROM pg_replication_slots;

-- 删除未使用的复制槽
SELECT pg_drop_replication_slot('slot_name');

-- 临时措施：删除旧WAL（危险，可能导致Standby无法同步）
-- SELECT pg_walfile_name_offset(pg_current_wal_lsn());
```

### 3. Standby延迟过大

**问题**：`replay_lag > 10s`

**排查**：
```sql
-- 1. 检查Primary写入量
SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0') / 1024 / 1024 AS total_wal_mb;

-- 2. 检查Standby是否有慢查询
SELECT pid, query_start, state, query 
FROM pg_stat_activity 
WHERE state != 'idle' 
ORDER BY query_start;

-- 3. 检查Standby硬件资源（CPU、IO）
```

**解决**：
```sql
-- 1. 停止Standby上的长查询
SELECT pg_terminate_backend(pid);

-- 2. 调整参数
ALTER SYSTEM SET max_standby_streaming_delay = '60s';
SELECT pg_reload_conf();

-- 3. 升级Standby硬件
```

### 4. 脑裂（Split-Brain）

**问题**：Primary和Standby同时可写

**预防**：
- 使用Patroni/pg_auto_failover自动管理
- 配置fencing（隔离故障节点）
- 监控Primary数量

**恢复**：
```bash
# 1. 立即停止其中一个Primary
sudo systemctl stop postgresql

# 2. 确定哪个是真正的Primary（数据最新）
# 3. 将另一个节点重建为Standby
```

---

## 📝 总结

### 架构选择建议

| 场景 | 推荐方案 | 说明 |
|------|---------|------|
| **测试环境** | 手动配置主从 | 简单，成本低 |
| **生产环境（小规模）** | pg_auto_failover | 官方推荐，简单易用 |
| **生产环境（大规模）** | Patroni + etcd | 功能强大，社区活跃 |
| **多数据中心** | Patroni + Consul | 支持跨DC部署 |

### 关键配置总结

```ini
# Primary核心配置
wal_level = replica
max_wal_senders = 10
synchronous_commit = on  # 同步复制
synchronous_standby_names = 'standby1'

# Standby核心配置
hot_standby = on
max_standby_streaming_delay = 30s
hot_standby_feedback = on
primary_conninfo = 'host=primary_ip ...'
primary_slot_name = 'standby1_slot'
```

---

**最后更新**：2026年10月17日  
**维护人**：数据库架构师  
**参考文档**：
- [PostgreSQL流复制官方文档](https://www.postgresql.org/docs/16/warm-standby.html)
- [Patroni官方文档](https://patroni.readthedocs.io/)
- [pg_auto_failover官方文档](https://pg-auto-failover.readthedocs.io/)
