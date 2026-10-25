# 劳务供应链通用后端底座

本目录采用 Java 17、Spring Boot、MyBatis Plus、PostgreSQL、Flyway 的模块化单体；通用底座与已放行的首批业务纵向切片并存。当前已实现身份上下文、组织入驻/成员、用工需求审核发布及 BP-04 应标/选标/确认交接切片；不包含履约、结算或支付闭环，不要求 Redis 或消息中间件。

设计边界与验收依据：[通用后端底座建设方案](../docs/02-开发阶段/技术方案/通用后端底座建设方案-v1.md)。

供应链定位下的能力矩阵、错误码目录、跨组织授权及版本/金额/可靠投递接入：
[劳务供应链平台底座能力与接入契约](docs/supply-chain-foundation.md)。业务术语见 [CONTEXT.md](../CONTEXT.md)。
金额类的允许与禁止使用场景见 [money 包使用指南](src/main/java/com/company/labor/common/money/README.md)。
高精度单价、中间计算、动态比例和尾差处理见 [计价精度契约](docs/pricing-precision.md)；不预置分成档位或税率。

## 代码注释要求

所有新增或修改代码必须遵守 [后端注释规则](AGENTS.md) 和 [全项目注释规范](../CLAUDE.md#注释规范强制)：
Java 类型、手写方法（含 private、重写方法和构造器）、字段与配置项必须有中文说明，关键安全、事务和并发逻辑解释设计原因。
record 组件通过类型 JavaDoc 的 `@param` 说明；不逐行翻译代码，不改写 Lombok 生成方法。

`mvn validate` 已对 `foundation/` 和 `common/` 下生产 Java 代码启用类型与方法 JavaDoc 缺失检查，包含 private 方法，
不豁免 `@Override`。其他目录同样受规则约束，但历史缺口尚未纳入此自动检查；字段、参数标签和注释准确性仍须审查。

## 目录职责与维护约定

- [src/](src/README.md)：正式源码、资源与测试；数据库版本变更统一使用 `src/main/resources/db/migration/` 的 Flyway 迁移。
- [logs/](logs/README.md)：本地运行日志，除说明外不提交。
- [docs/](docs/supply-chain-foundation.md)：后端能力契约与接入说明；产品需求和全项目技术方案仍归仓库根 `docs/`。
- `target/`：Maven 构建、测试与覆盖率产物，由构建工具生成并忽略；`mvn clean` 会删除它，不在其中维护 README 或任何唯一资料。
- `pom.xml`、`checkstyle.xml`、`compose.yml`、`compose.test.yml` 分别维护依赖与验证、代码风格、本地开发数据库和隔离测试数据库；变更应同步说明和测试。
- 必须保持组织隔离、权限校验、审计和错误契约；基础能力与业务规则分离，业务接入遵守下文准入要求。

### 目录规划原则

本节是新增代码和文件的放置指南，不是要求立即迁移现有代码。保持单 Maven 工程、模块化单体；只在真实需求出现时新增目录，不预建空业务模块、空分层或 Maven 子模块。业务目录的规划不代表相关需求已经获准实现，新增业务仍须满足第 3 节接入约束。

Java 包根统一为 `com.company.labor`，**先按能力或业务模块归类，再在模块内部按需分层**。禁止在包根新增汇集全系统代码的 `controller`、`service`、`mapper`、`entity` 或 `dto` 包。

### 目标目录与职责

以下为放置模板，带“按需新增”的目录目前不要求存在；`<module>`、`<capability>` 是占位符，实际使用明确的小写英文名称。

```text
backend/
├── pom.xml / checkstyle.xml / compose.yml / compose.test.yml
├── src/
│   ├── main/
│   │   ├── java/com/company/labor/
│   │   │   ├── LaborApplication.java
│   │   │   ├── common/                    通用契约与无业务归属的纯工具
│   │   │   │   ├── error/                 稳定错误契约与平台错误目录
│   │   │   │   ├── money/                 单价、中间计算、最终金额与守恒分配
│   │   │   │   └── resource/              不含所有权假设的资源引用
│   │   │   ├── foundation/                非业务基础能力
│   │   │   │   ├── access/                资源授权扩展点与独立复核守卫
│   │   │   │   ├── audit/                 操作审计
│   │   │   │   ├── context/               执行身份与组织上下文
│   │   │   │   ├── crypto/                加密与密钥使用
│   │   │   │   ├── persistence/           通用持久化支持
│   │   │   │   ├── reliability/           幂等与可靠执行
│   │   │   │   ├── security/              认证、权限与令牌撤销
│   │   │   │   ├── snapshot/              资源级授权的追加式版本快照
│   │   │   │   ├── storage/               私有文件存取
│   │   │   │   ├── tasks/                 可靠任务调度
│   │   │   │   └── web/                   Web 协议与序列化支持
│   │   │   └── modules/                   业务模块，按需新增
│   │   │       └── <module>/              一个明确的业务职责
│   │   │           ├── api/               供其他模块调用的接口与契约，按需新增
│   │   │           ├── controller/        HTTP 入口及请求、响应模型
│   │   │           ├── application/       用例编排、事务与权限协调
│   │   │           ├── domain/            业务规则、领域模型
│   │   │           └── persistence/       Mapper、数据库实体与持久化实现
│   │   └── resources/
│   │       ├── application*.yml           应用配置
│   │       ├── logback-spring.xml         日志配置
│   │       ├── db/migration/              全工程统一编号的 Flyway 迁移
│   │       └── mapper/<module>/           业务 Mapper XML，按需新增
│   ├── test/
│   │   ├── java/com/company/labor/         按被测代码的包结构组织
│   │   └── resources/                     测试专用配置和合成数据，按需新增
│   └── README.md
├── logs/                                 本地日志，不存放源码或唯一资料
└── target/                               自动生成的构建产物
```

当前已落地 `modules/auth`（手机号账号、Mock SMS、RS256 会话和显式身份绑定准备）、`modules/identity`（组织、成员和审核）、`modules/demand`（需求版本、审核、按租户投递、发布和暂停/关闭）与 `modules/bidding`（应标版本、独立选标、绝对配额、供应商确认、监督证据和 BP-05 快照交接）。本地账号认证默认关闭，必须显式配置密钥后启用；认证模块不接入真实短信或真实微信，也不实现工人/经纪人业务关系。供应商读取使用发布时生成的目标租户投递快照，不跨租户查询需求源表。比例配额因需求版本尚未承载取整/余数合同而明确拒绝。

| 位置 | 应当放置 | 不应放置 |
|---|---|---|
| Java 包根 | 应用启动入口 | 业务类、全局 Service 或 Mapper 集合 |
| `common` | `Result`、`PageResult`、通用错误契约、字符串或集合等纯工具 | 某业务专属规则、数据库访问、外部系统调用 |
| `foundation/<capability>` | 可复用且不承载业务政策的技术能力，以及其专属配置、过滤器、任务基础设施 | 招聘条件、结算规则、业务对象授权决策 |
| `modules/<module>` | 单一业务模块的用例、规则、数据访问及模块专属配置、任务处理器 | 其他模块的内部实现、跨系统杂项 |
| `resources` | 配置、迁移、必要的类路径资源 | Java 源码、真实密钥、运行时上传文件、生产数据 |
| `test` | 对应模块的测试、测试辅助类和合成数据 | 被生产代码依赖的实现 |

现有 `common/config`、`common/filter`、`common/context`、`common/exception` 中仍有框架集成和上下文桥接代码，部分依赖 `foundation`；它们属于现状兼容，不代表新增基础能力也应放入 `common`。本次不搬迁这些类，也不声称现有依赖已经单向化。后续触及其职责调整时，再单独评估迁移和兼容测试。

### 新增文件如何决定位置

1. **先找职责归属**：只服务某个业务的代码放 `modules/<module>`；无业务规则但需要框架、数据库或外部资源的基础能力放 `foundation/<capability>`；没有业务归属的通用契约或纯工具才考虑 `common`。不要仅因为“以后可能复用”就上提为公共代码。
2. **再找已有子包**：同一职责的代码优先放在已有包中；模块专属 DTO、异常、枚举、配置和任务处理器跟随模块，不集中堆进 `common` 或全局 `dto`、`exception`、`config` 包。
3. **按实际职责新增子包**：业务模块内部按需使用目录模板中的分层；请求和响应模型增多时可拆为 `controller/request`、`controller/response`，不要为了凑层级创建只有透传职责的类或接口。
4. **出现新业务职责才新增模块**：新功能若拥有独立规则、用例和数据归属，且无法合理归入已有模块，应先明确职责和对外接口，再新增 `modules/<module>`；不要按一张表或一个接口机械建模块。
5. **同步配套文件**：新增测试放到相应测试包；需要手写 SQL 时，XML 放 `resources/mapper/<module>/`，其 `namespace` 对应 Mapper 全限定名；数据库变更始终新增到统一的 `db/migration/`，不按业务拆出独立版本序列。

业务模块内部的职责约定：

- `controller` 只负责协议转换、参数校验和调用用例，不直接调用 Mapper 或编写业务规则；请求和响应模型不直接使用数据库实体。
- `application` 负责用例编排、事务和调用基础能力；`domain` 聚焦业务规则，不依赖 Controller、HTTP 请求对象或具体持久化实现。
- `persistence` 封装 Mapper、数据库实体和查询实现；确有隔离需要时，领域所需的持久化接口放 `domain`，实现放 `persistence`，不要求每个类都配一个接口。
- `api` 仅在发生真实跨模块调用时建立，包含稳定调用接口和必要契约；实现留在模块内部，不通过这个包暴露 Mapper 或数据库实体。

### 依赖与拆包规则

- 新增代码的依赖方向为 `modules → foundation / common`、`foundation → common`；不得新增 `common → foundation / modules` 或 `foundation → modules` 的直接实现依赖。基础能力需要业务策略时，由基础能力定义小接口，业务提供实现，例如 `FileAccessPolicy`、`TaskHandler`。
- 跨业务模块只调用对方明确公开的 `api` 契约，不直接引用对方的 Controller、Mapper、数据库实体或内部实现；禁止跨模块直接操作对方拥有的数据表，禁止循环依赖。
- 保持小接口、内聚实现；代码只被一个模块使用时留在模块内部。确有多个调用方共享相同语义时，再评估提取公共能力，不因少量代码相似就抽出公共模块。
- 当一个包包含多种独立职责、修改通常只涉及其中一组类，或名称越来越依赖 `Manager`、`Helper`、`Misc` 才能区分时，应评估按职责拆包。文件数量只作为评审信号，不设机械的文件数上限，也不把一次拆包变成全量重构。
- 新增业务模块、基础能力包或改变目录职责时，同步更新本节；普通文件新增若遵循已有职责，不要求逐个登记。复杂模块再增加模块级 README，说明职责、公开接口和依赖限制，本节作为总入口。

### 放置示例

以下业务路径仅演示获批需求的归属，不代表本次创建目录或实现业务：

| 新增内容 | 放置位置 |
|---|---|
| 私有文件存储的新实现 | `src/main/java/com/company/labor/foundation/storage/` |
| 通用、无副作用的字符串处理函数 | 优先评估现有 `common/util/StringUtil.java`，不要重复新建工具类 |
| 招聘查询接口与请求模型 | `src/main/java/com/company/labor/modules/recruitment/controller/` 及其按需新增的 `request/` |
| 招聘查询用例与数据库 Mapper | 分别放 `modules/recruitment/application/`、`modules/recruitment/persistence/` |
| 招聘查询的 Mapper XML | `src/main/resources/mapper/recruitment/` |
| 招聘模块的可靠任务处理器 | `src/main/java/com/company/labor/modules/recruitment/application/`，任务调度底座仍在 `foundation/tasks/` |
| 招聘用例的单元测试 | `src/test/java/com/company/labor/modules/recruitment/application/` |
| 新增业务表迁移 | `src/main/resources/db/migration/V<下一个未占用版本>__<变更说明>.sql`，保持全工程版本唯一 |

## 禁止事项

- 禁止把招聘、奖励、履约或支付规则直接塞入通用底座，或把制度草案实现为已批准的付款规则。
- 禁止信任客户端自行声明的组织身份、绕过权限/组织隔离，或为方便调试开放免认证入口。
- 禁止提交生产数据、真实密钥和日志；禁止手改构建产物或修改已应用迁移来替代新增迁移。
- 禁止通过跳过测试、降低覆盖率门禁掩盖缺陷，或将底座验证通过等同于业务上线批准。

## 1. 本地启动

需要 JDK 17、Maven、PostgreSQL 16；也可使用本目录的 Compose 或 Podman Compose 启动数据库。以下是 PowerShell 示例，密码应自行生成并通过环境注入：

```powershell
$env:JAVA_HOME = '你的 JDK 17 目录'
$env:DB_PASSWORD = '自行生成的本地数据库密码'
podman compose -f compose.yml up -d
mvn spring-boot:run '-Dspring-boot.run.profiles=local'
```

开发数据库使用 `compose.yml` 的 `labor_db`，不要把它用于集成测试。测试数据库使用单独的 Compose 文件、容器、卷、数据库和端口：

```powershell
$env:TEST_DATABASE_PASSWORD = '临时生成的测试数据库密码'
podman compose -f compose.test.yml up -d
podman compose -f compose.test.yml ps
$env:TEST_DATABASE_URL = 'jdbc:postgresql://localhost:55444/labor_foundation_test'
$env:TEST_DATABASE_USERNAME = 'labor_test'
mvn -Pintegration clean verify
```

测试结束后停止容器；测试会清空该 `_test` 数据库中的技术表和业务测试表，禁止把 `TEST_DATABASE_URL` 指向开发或生产数据库：

```powershell
$env:TEST_DATABASE_PASSWORD = '首次启动时设置的同一测试密码'
podman compose -f compose.test.yml down
```

命名卷会保留测试数据和初始化密码；后续任何 Compose 操作都先设置同一 `TEST_DATABASE_PASSWORD`。只有需要重建空测试库时才使用 `podman compose -f compose.test.yml down -v`。

以上命令在 `backend/` 目录执行，日志默认写入 `backend/logs/`。如必须从仓库根目录启动，需显式指定日志目录：

```powershell
$env:LOG_PATH = 'backend/logs'
mvn -f backend/pom.xml spring-boot:run '-Dspring-boot.run.profiles=local'
```

默认数据库为 `jdbc:postgresql://localhost:5432/labor_db`，用户为 `labor`；可用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 覆盖。数据库密码没有默认值。Flyway 首次启动创建技术表，不创建业务表。

- API 前缀：`/api`；匿名健康检查：`GET /api/actuator/health`。
- `local` 开启 `/api/swagger-ui/index.html` 和 `/api/v3/api-docs`，仍需 Bearer 认证。
- 认证开关关闭时，除健康检查外接口均要求 Bearer 身份；启用认证后，注册、登录、找回及 CSRF/刷新入口按认证控制器的会话与 CSRF 规则开放，账号和组织读取仍要求本地 Access Token。`/api/actuator/**` 还需 `foundation:observe`。
- 未配置 JWT 公钥时，所有令牌均拒绝，没有本地免认证开关。
- `GET /api/actuator/health/readiness` 可用于就绪检查；默认不输出依赖详情。

## 2. 认证与安全配置

业务接口继续作为资源服务器验证 **RS256 JWT**。此外，`modules/auth` 提供可选的本地手机号账号认证：`AUTH_ENABLED` 默认 `false`，开启后由本地服务签发短期 RS256 Access Token，并用 HttpOnly Refresh Cookie 轮换会话。现有外部身份与本地账号不会自动合并；生产身份迁移、真实短信和微信登录不在本次实现范围内。

| 环境变量 / 配置 | 要求 |
|---|---|
| `JWT_PUBLIC_KEY` | X.509 PEM RSA 公钥资源，例如 `file:/absolute/path/public.pem` |
| `JWT_ISSUER` | 必须与 JWT `iss` 一致，默认 `labor-identity` |
| `JWT_AUDIENCE` | 必须包含于 JWT `aud`，默认 `labor-backend` |
| `AUTH_ENABLED` | 是否启用本地账号端点，默认 `false` |
| `AUTH_PRIVATE_KEY` / `AUTH_PUBLIC_KEY` | 本地签发 RSA PKCS#8 私钥与 X.509 公钥资源；启用认证时必填且必须成对 |
| `AUTH_JWT_ISSUER` / `AUTH_JWT_AUDIENCE` | 本地令牌发行者与受众；需与资源服务器对应验证配置一致 |
| `AUTH_PHONE_HMAC_KEY` | 手机号索引摘要密钥，启用认证时至少 32 UTF-8 字节；须独立于数据加密密钥 |
| `AUTH_MOCK_SMS_ENABLED` | 仅 `local`/`test` profile 可启用；生产 profile 拒绝 Mock SMS |
| `foundation.authentication.refresh-cookie-secure` | HTTPS 环境必须为 `true`；仅本地 HTTP 联调可显式设为 `false` |
| `foundation.security.allowed-origins` | CORS 精确来源列表；默认不允许跨域，不接受 `*` |
| `STORAGE_ROOT` | 私有文件根目录，不配置为静态资源或 Web 服务器公开目录 |
| `CRYPTO_ACTIVE_KEY` | AES 活动密钥版本 |
| `foundation.crypto.keys.<version>` | 外部提供的 Base64 AES 密钥，支持 16/24/32 字节 |
| `TASKS_ENABLED` | 是否开启轮询工作器，默认 `false` |

必要 JWT 声明：`iss`、`aud`、`exp`、`jti`、正整数形式的 `sub`。可选 `tenant_id` 为正整数，`permissions` 为字符串数组，例如 `["foundation:files:read"]`。访问需要组织上下文的能力时，`tenant_id` 必须存在。权限是精确字符串，不通过 `admin` 角色绕过。

本地 `/api/auth/context` 会在签发组织上下文前重新检查账号、组织与成员状态；外部身份发行方也必须遵守同样的成员校验要求。客户端组织请求头不参与身份构造。`TokenRevocations.revoke(jti, expiry)` 支持持久化撤销；撤销存储不可用时拒绝认证。组织成员变更后的即时撤销仍需按具体业务授权在服务端复核，不能仅依赖已有 JWT 声明。

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
- 新错误使用 `ErrorDefinition` 与 `PlatformError`：数字业务码、`errorKey`、HTTP 状态分离，并关联 `requestId`。
  成功仍为 `code=200`，失败不能再用 `code==401` 判断认证；历史数字工厂保留兼容。完整分配与迁移规则见能力契约第 3 节。
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

以下测试命令均在 `backend/` 目录执行：

```powershell
mvn test
$env:TEST_DATABASE_URL = 'jdbc:postgresql://localhost:5432/labor_foundation_test'
$env:TEST_DATABASE_USERNAME = '你的测试数据库账号'
$env:TEST_DATABASE_PASSWORD = '你的测试数据库密码'
mvn -Pintegration clean verify
```

集成测试 **会清空技术测试表和隔离测试表**；仅允许数据库名以 `_test` 结尾的独立 PostgreSQL 数据库。不要提供生产或共享开发数据库凭据。没有 PostgreSQL 可以只运行单元测试，不代表集成验证通过。

CI 使用 Java 17 + PostgreSQL 16，执行 Checkstyle、全部单元测试、打包、Failsafe 集成测试和 JaCoCo 报告，失败不跳过。报告在 `target/surefire-reports`、`target/failsafe-reports`、`target/site/jacoco/index.html`。覆盖率统计全部项目类，不对第三方 SQL 解析器做字节码插桩；Maven `verify` 强制检查全后端 **LINE ≥80%**，未排除低覆盖类。仅运行 `mvn test` 不执行完整集成与覆盖门禁。

2026-10-25 历史验证记录：Podman 隔离测试数据库通过 `compose.test.yml` 提供；在 `labor_foundation_test` 上执行
`mvn -Pintegration clean verify`，Flyway V1～V7 迁移、170 项单元测试、44 项集成测试（其中 BP-04 为 16 项）和 JaCoCo 门禁全部通过，Checkstyle 为 0 违规。
不带 `-Pintegration` 的 `mvn clean verify` 不执行 `*IT`，当前覆盖率约 51% 并按门禁失败；它不能替代完整命令。

2026-10-26 完整认证验收记录：独立 Podman PostgreSQL（固定端口 55444）验证 Flyway V1～V9；`mvn -Pintegration clean verify` 的 179 项单元测试、57 项集成测试与 JaCoCo 门禁通过，Checkstyle 为 0 违规。Web type-check、production build、lint 全部通过；Playwright 验证注册、Mock SMS、找回并重置密码、密码登录、短信登录、刷新后恢复会话、退出和未认证访问重定向。此记录仅覆盖 local/test，不表示可生产启用。

认证功能需要设置 `AUTH_ENABLED=true`、本地 RSA 私钥/公钥、至少 32 UTF-8 字节的 `AUTH_PHONE_HMAC_KEY`、数据加密密钥和 `AUTH_MOCK_SMS_ENABLED=true`，仅可使用 `local`/`test` profile；生产 profile 会拒绝 Mock SMS。账号、挑战、Refresh 会话、安全事件和显式身份绑定票据属于跨组织共享认证表。挑战/重置保留期默认 24 小时，会话 90 天，安全事件 730 天；每日清理仅删除超过各自期限的数据，不删除未结业务凭证。

### 业务开发准入

通用底座已经完成独立的开发准入验证；本轮首批业务切片已按业务包清单放行，BP-04 已在独立 `_test` PostgreSQL 上完成局部联调验证。新迁移和业务集成测试必须在独立 `_test` PostgreSQL 上重新验证后，才能把后续切片标记为联调通过。**可以开始业务模块开发，不代表生产上线批准。**

新增模块必须按第 3 节落实可信身份与成员关系、具体权限/资源 ACL、隔离表注册、任务实时授权与幂等，并提供迁移及回归测试。后续每次交付均应执行完整命令，不能用定向测试或单元测试成功替代准入验证。

## 5. 兼容性与交付边界

- 已移除固定密钥 ECB 加密、静态 `JsonUtil`、进程内 `IdGenerator` 和参数/返回值日志切面，分别使用 `CryptoService`、`JsonCodec`、`SequenceIds` 和最小化请求日志。旧 ECB 密文不自动兼容，若已有数据需另做离线迁移。
- Checkstyle 修正了旧配置的无效层级及过时选项，保留可执行的编码、命名、导入、行长和金额类型规则；不新增格式化工具。
- 保留旧工具类供兼容，不代表它们全部经过本轮重新设计。`MoneyUtil` 加减法保留输入精度，乘除法默认两位小数舍入，不适合不允许中间舍入的通用高精度计算。
- 新交易的最终金额使用 `common/money/Money`，按币种精确到最小单位；单价与中间值分别使用 `UnitPrice`、`CalculatedAmount`，不能提前按分舍入。
- 比例全部由业务显式提供，无固定档位或默认值；`CalculationPolicy` 指定除法与结算舍入，`MoneyAllocation` 提供两种显式选择的守恒分配方法。
- 新计价不得使用已弃用的 `MoneyUtil.multiply/divide` 默认两位乘除；高精度值 JSON 传十进制字符串，旧 `yuanToFen` 对分以下非零精度及溢出拒绝而非截断。
- `ResourceAccessGuard` 默认拒绝未知资源策略，平台角色无隐含全局权限；`VersionedSnapshots` 保留历史版本但不代表审批、签约、结算或付款成功。
- `TaskQueue.enqueueTransactional` 强制同库现有事务，业务修改与投递共同提交；外部处理仍至少一次，必须另行设计幂等、未知态和补偿。
- 当前不是“生产就绪”承诺；生产前仍需依赖漏洞与补丁评估、容量与容灾演练、密钥托管、日志访问控制、备份恢复和完整身份/业务资源授权接入。迁移账号与运行账号应分离并采用最小权限，不能把开发数据库的 DDL 权限当成生产默认配置。
