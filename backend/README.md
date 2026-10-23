# 劳务供应链通用后端底座

本目录只实现非业务基础能力。采用 Java 17、Spring Boot、MyBatis Plus、PostgreSQL、Flyway 的模块化单体；不包含招聘、履约、结算、支付等业务闭环，不要求 Redis 或消息中间件。

设计边界与验收依据：[通用后端底座建设方案](../docs/02-开发阶段/技术方案/通用后端底座建设方案-v1.md)。

## 1. 本地启动

需要 JDK 17、Maven、PostgreSQL 16；也可使用本目录的 Compose 启动数据库。以下是 PowerShell 示例，密码应自行生成并通过环境注入：

```powershell
$env:JAVA_HOME = '你的 JDK 17 目录'
$env:DB_PASSWORD = '自行生成的本地数据库密码'
docker compose up -d
mvn spring-boot:run '-Dspring-boot.run.profiles=local'
```

默认数据库为 `jdbc:postgresql://localhost:5432/labor_db`，用户为 `labor`；可用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 覆盖。数据库密码没有默认值。Flyway 首次启动创建技术表，不创建业务表。

- API 前缀：`/api`；匿名健康检查：`GET /api/actuator/health`。
- `local` 开启 `/api/swagger-ui/index.html` 和 `/api/v3/api-docs`，仍需 Bearer 认证。
- 除健康检查外，接口默认全部认证；`/api/actuator/**` 还需 `foundation:observe`。
- 未配置 JWT 公钥时，所有令牌均拒绝，没有本地免认证开关。
- `GET /api/actuator/health/readiness` 可用于就绪检查；默认不输出依赖详情。

## 2. 认证与安全配置

本工程是资源服务器，验证外部可信身份服务签发的 **RS256 JWT**，不签发令牌、不提供登录或组织成员管理。

| 环境变量 / 配置 | 要求 |
|---|---|
| `JWT_PUBLIC_KEY` | X.509 PEM RSA 公钥资源，例如 `file:/absolute/path/public.pem` |
| `JWT_ISSUER` | 必须与 JWT `iss` 一致，默认 `labor-identity` |
| `JWT_AUDIENCE` | 必须包含于 JWT `aud`，默认 `labor-backend` |
| `foundation.security.allowed-origins` | CORS 精确来源列表；默认不允许跨域，不接受 `*` |
| `STORAGE_ROOT` | 私有文件根目录，不配置为静态资源或 Web 服务器公开目录 |
| `CRYPTO_ACTIVE_KEY` | AES 活动密钥版本 |
| `foundation.crypto.keys.<version>` | 外部提供的 Base64 AES 密钥，支持 16/24/32 字节 |
| `TASKS_ENABLED` | 是否开启轮询工作器，默认 `false` |

必要 JWT 声明：`iss`、`aud`、`exp`、`jti`、正整数形式的 `sub`。可选 `tenant_id` 为正整数，`permissions` 为字符串数组，例如 `["foundation:files:read"]`。访问需要组织上下文的能力时，`tenant_id` 必须存在。权限是精确字符串，不通过 `admin` 角色绕过。

身份发行方必须先验证成员关系再签发组织上下文。客户端的组织请求头不参与身份构造。`TokenRevocations.revoke(jti, expiry)` 支持持久化撤销；撤销存储不可用时拒绝认证。组织成员变更后的即时撤销需由后续身份模块集成，当前不承诺自动联动。

配置加密密钥可以使用受保护的外部 YAML，并通过 `SPRING_CONFIG_ADDITIONAL_LOCATION` 加载；不要将真实密钥提交仓库：

```yaml
foundation:
  crypto:
    active-key: key-2026
    keys:
      key-2026: ${CRYPTO_KEY_2026}
```

使用 `prod` profile 部署：必须配置 JWT 公钥、有效活动加密密钥、绝对私有存储路径，否则启动失败。任务租约必须为正，重试等待不能为负。无论哪个 profile 都没有硬编码加密密钥或绕过认证逻辑。

## 3. 业务模块接入约束

### 上下文和数据隔离

- 使用 `ExecutionContext.require()`、`requireTenant()`、`checkPermission(...)` 和 `@PreAuthorize`，不要自行相信传入的用户/组织编号。
- MyBatis 表必须在 `foundation.tenant-tables` 或 `foundation.shared-tables` 注册；未注册表直接拒绝。共享表不受组织隔离，应谨慎审查其访问权限。
- 隔离表包含 `tenant_id`；MyBatis 自动注入 INSERT 和查询/更新/删除条件。隔离表 INSERT 不允许手动列出 `tenant_id`，UPDATE 不允许修改它，不支持隔离表 upsert。
- 实体 `tenantId` 应配置 `@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)`；新建 DTO 不接受此字段。
- 原生 JDBC、手工 SQL、`@InterceptorIgnore` 不受自动隔离保护；新增此类访问必须显式限域并补充越权测试。技术模块中的 JDBC 各自负责作用域，任务领取和令牌撤销属于受控全局操作。
- 分页使用 `PageQuery`（页码 ≥1，每页 1–200）及 MyBatis `Page`；需乐观锁的实体声明 `@Version`。

### HTTP 与序列化

- JSON API 使用 `Result<T>`；异常返回真实 HTTP 状态。文件下载、Actuator 和 OpenAPI 不强制包裹 `Result`。
- `Long` 和 `long` 均序列化为字符串，避免前端编号精度丢失；金额与精度计算使用 `BigDecimal`。
- JSON 请求体上限 1 MiB（包含无 Content-Length 的请求），multipart 单文件 10 MiB、总请求 11 MiB；存储服务另有可配置上限。
- 参数校验不回显被拒绝的敏感值；日志不记录请求体、响应体或 Bearer 内容。反向代理也应配置请求大小、速率、连接和读取超时。
- `X-Request-Id` 只接受 1–64 位字母、数字、下划线或短横线，否则重新生成。请求结束清除线程上下文，异步任务不继承请求线程身份。
- `JsonCodec` 复用统一 ObjectMapper，编码失败抛错；规范编码对 Map 排序，不承诺不同 DTO 类型的语义等价。

### 编号、幂等与审计

- `SequenceIds.nextId()` / `nextReference(prefix)` 使用 PostgreSQL 序列，跨实例唯一但可有间隙，不代表排序、业务数量或连续账本。
- `IdempotentExecutor.execute(operation, key, request, resultType, action)` 在同一数据库事务中执行操作并缓存结果。键作用域含组织、操作者和操作；相同键不同请求摘要返回 409。
- 调用方应先校验权限，再进入幂等执行。回放不是绕过当前资源权限的理由。`operation` 和请求 DTO 结构必须稳定，变更结构应升级操作版本。
- `action` 只适用于同库事务效果，不包含短信、支付、对象存储等远程效果的原子性保证。当前幂等记录不自动过期，清理前必须明确允许重复执行的保留策略。
- `AuditRecorder.record(action, resourceType, resourceId, outcome)` 自动记录当前身份和请求编号，只接受有限标识及 `SUCCESS` / `DENIED` / `FAILED`。不要传入任意请求体或证件值。
- 审计参与当前事务；回滚事务中的审计也会回滚。它不是防篡改账本，也不是独立持久化失败审计通道。

### 可靠任务

实现 `TaskHandler` 并注册 Spring Bean，通过 `TaskQueue.enqueue(type, payload)` 提交；类型不能重复，payload 上限 64 KiB。提交参与当前事务，回滚不投递。开启工作器后定期领取，`status(id)` 只允许提交者在原组织下查询。

领取使用 `FOR UPDATE SKIP LOCKED` 和独立事务；租约过期可重新领取，完成更新受租约令牌保护；失败按配置有限重试。语义是 **至少一次**，不是恰好一次。没有租约续期或强制中断，处理器必须设置超时、提供幂等性，租约应大于合理执行时间。

配置：`foundation.tasks.max-attempts`（默认 5）、`lease`（默认 `5m`）、`retry-delay`（默认 `30s`）、`poll-delay-ms`（默认 1000）。执行恢复提交时的 actor/tenant 和 requestId，但权限集合为空；需要访问业务资源的处理器必须重新授权，不把历史快照当成当前成员权限。

### 加密与私有文件

- `CryptoService.encrypt(plaintext, purpose)` 使用随机 nonce 的 AES-GCM；解密必须使用相同 purpose，密文篡改失败。版本化密文支持保留旧密钥解密和活动密钥轮换；purpose 应稳定并绑定具体用途/字段，不能从不可信请求自由选择。
- `POST /api/foundation/files`：multipart 字段 `file`，需 `foundation:files:write`。
- `GET /api/foundation/files/{id}`：受控附件下载，需 `foundation:files:read`。
- 文件名只作为展示元数据，物理路径由随机标识生成；当前组织先硬隔离，默认只有上传者能下载。`FileAccessPolicy` 可扩展同组织的资源授权，每次下载重新检查。
- 文件 API 不实现业务附件归属关系。引入业务资源后，必须明确关联和资源授权，不能将“拥有文件编号”视为授权。
- 适配器为本地私有磁盘，多实例需共享卷，不能使用各自独立磁盘。目录权限、静态资源隔离和备份由部署负责。
- 普通异常/数据库回滚会清理文件；进程硬崩溃在移动文件与数据库提交之间可能留下孤儿文件，磁盘故障也可能破坏清理。当前不自动扫描删除，需运维核对元数据后清理。数据库与文件必须协调备份恢复。

## 4. 测试与 CI

```powershell
mvn test
$env:TEST_DATABASE_URL = 'jdbc:postgresql://localhost:5432/labor_foundation_test'
$env:TEST_DATABASE_USERNAME = '你的测试数据库账号'
$env:TEST_DATABASE_PASSWORD = '你的测试数据库密码'
mvn -Pintegration clean verify
```

集成测试 **会清空技术测试表和隔离测试表**；仅允许数据库名以 `_test` 结尾的独立 PostgreSQL 数据库。不要提供生产或共享开发数据库凭据。没有 PostgreSQL 可以只运行单元测试，不代表集成验证通过。

CI 使用 Java 17 + PostgreSQL 16，执行 Checkstyle、全部单元测试、打包、Failsafe 集成测试和 JaCoCo 报告，失败不跳过。报告在 `target/surefire-reports`、`target/failsafe-reports`、`target/site/jacoco/index.html`。覆盖率统计全部项目类，不对第三方 SQL 解析器做字节码插桩；Maven `verify` 强制检查全后端 **LINE ≥80%**，未排除低覆盖类。仅运行 `mvn test` 不执行完整集成与覆盖门禁。

2026-10-23 本机以 Java 17 + 独立 PostgreSQL 16.4 执行 `mvn -B -ntp -Pintegration clean verify` 成功：116 项单元测试 + 18 项集成测试全部通过，失败/错误/跳过均为 0；Checkstyle 0 违规。单次 clean 构建的全后端行覆盖 **86.43%（1051/1216）**，分支覆盖 **76.63%（600/783）**。原 7 项存量失败已修复，并增加断言空值相等回归测试；未降低校验强度。远端 CI 尚未执行。

### 业务开发准入

当前满足通用底座的开发准入：完整验证通过、行覆盖门禁达标、真实数据库隔离与可靠执行测试通过、接入契约和边界已记录。**可以开始业务模块开发，不代表生产上线批准。**

新增模块必须按第 3 节落实可信身份与成员关系、具体权限/资源 ACL、隔离表注册、任务实时授权与幂等，并提供迁移及回归测试。后续每次交付均应执行完整命令，不能用定向测试或单元测试成功替代准入验证。

## 5. 兼容性与交付边界

- 已移除固定密钥 ECB 加密、静态 `JsonUtil`、进程内 `IdGenerator` 和参数/返回值日志切面，分别使用 `CryptoService`、`JsonCodec`、`SequenceIds` 和最小化请求日志。旧 ECB 密文不自动兼容，若已有数据需另做离线迁移。
- Checkstyle 修正了旧配置的无效层级及过时选项，保留可执行的编码、命名、导入、行长和金额类型规则；不新增格式化工具。
- 保留旧工具类供兼容，不代表它们全部经过本轮重新设计。`MoneyUtil` 加减法保留输入精度，乘除法默认两位小数舍入，不适合不允许中间舍入的通用高精度计算。
- 当前不是“生产就绪”承诺；生产前仍需依赖漏洞与补丁评估、容量与容灾演练、密钥托管、日志访问控制、备份恢复和完整身份/业务资源授权接入。迁移账号与运行账号应分离并采用最小权限，不能把开发数据库的 DDL 权限当成生产默认配置。
