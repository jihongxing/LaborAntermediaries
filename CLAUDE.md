# CLAUDE.md - 技术规范与开发约束

> 2026-10-23 后端底座更新：`backend/README.md` 与 `docs/02-开发阶段/技术方案/通用后端底座建设方案-v1.md` 是当前通用后端能力的接入依据。下文旧 SaaS 示例不作为继续新增业务模型的授权。发生冲突时，以本段及上述方案为准：编号使用数据库 `SequenceIds`，私有文件使用随机 UUID；敏感字段使用外部密钥 `CryptoService`，JSON 使用 `JsonCodec`，身份使用 `ExecutionContext`，请求日志使用最小化 `RequestIdFilter`，不再使用 `IdGenerator`、ECB 或 `LogAspect`。`MoneyUtil` 的两位舍入不是全部精度计算的强制策略。基础设施技术表允许按功能设计主键、保留及生命周期，不强制软删除；受控基础设施 JDBC 显式限域，不能推广为业务跨租户绕过。Redis/RabbitMQ 非当前启动必需依赖；业务开发仍必须遵循单组织上下文、权限校验和数据隔离。文件下载/运维接口可采用协议原生响应。

## 📋 文档说明

本文档定义项目的**技术规范、开发约束、代码标准**，所有开发人员和AI助手必须严格遵守。

---

## 🏗️ 技术架构约束

### 后端技术栈（强制）

| 技术 | 版本 | 说明 | 原因 |
|------|------|------|------|
| **Java** | JDK 17+ | 开发语言 | LTS版本，稳定可靠 |
| **Spring Boot** | 3.2+ | 开发框架 | 主流框架，生态完善 |
| **MyBatis Plus** | 3.5+ | ORM框架 | 代码生成，提高效率 |
| **PostgreSQL** | 16+ | 关系数据库 | 企业级数据库，强大的JSON/事务/分区支持 |
| **Redis** | 7.0+ | 缓存 | 缓存、分布式锁 |
| **RabbitMQ** | 3.12+ | 消息队列 | 异步处理 |

### 前端技术栈（强制）

| 端口 | 技术栈 | UI组件库 | 说明 |
|------|--------|---------|------|
| **Web后台** | Vue 3 + TypeScript + Vite | Element Plus | 管理后台标准栈 |
| **移动App** | Flutter 3.x | Material Design | 一套代码编译iOS/Android |
| **微信小程序** | uni-app 3.x | uni-ui | 一套代码编译多平台 |

### 禁止使用的技术

❌ **禁止使用**：
- ~~Struts、SSH等老旧框架~~
- ~~JSP、Servlet直接开发~~
- ~~jQuery（Web前端）~~
- ~~原生Android/iOS开发（必须用Flutter）~~
- ~~PHP、Python、Node.js后端（必须用Java）~~

---

## 🗄️ 数据库设计规范（强制）

### 多租户设计（核心约束）

**所有业务表必须包含`tenant_id`字段**：

```sql
CREATE TABLE xxx (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  -- 其他字段...
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMPTZ
);

-- 必须添加的索引和约束
CREATE INDEX idx_xxx_tenant_id ON xxx(tenant_id);
COMMENT ON TABLE xxx IS '表描述';
COMMENT ON COLUMN xxx.id IS '主键';
COMMENT ON COLUMN xxx.tenant_id IS '租户ID（必须）';
COMMENT ON COLUMN xxx.created_at IS '创建时间';
COMMENT ON COLUMN xxx.updated_at IS '更新时间';
COMMENT ON COLUMN xxx.deleted_at IS '删除时间（软删除）';

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

**强制要求**：
1. ✅ 所有业务表必须有`tenant_id`字段
2. ✅ 所有SQL查询必须自动注入`WHERE tenant_id = ?`
3. ✅ 使用MyBatis Plus的租户插件自动处理
4. ✅ 数据隔离是**安全红线**，必须100%保证

### 平台模式下的跨组织协作（强制，2026-10-19 新增）

平台模式（见 `docs/01-售前阶段/需求分析/requirements-draft-v0.3.md`）中，需求、应标、中标快照、结算单、分账指令和回执要在用工方、劳务公司、平台之间流转。原则：不放宽租户隔离，跨组织数据只能投递，不能跨租户查询。

1. ✅ **租户 = 组织**：每个用工方、每个劳务公司各是一个租户，平台运营是独立的平台租户
2. ✅ **个人用户走成员关系**：工人、个人经纪人是平台级账号，不是租户；通过"用户-租户成员关系"访问数据，每次请求只在一个已授权租户的上下文中执行；跨公司汇总（如经纪人查看多家劳务公司的返费）由客户端按成员关系逐个租户请求后合并
3. ✅ **跨组织数据只投递**：数据只写在归属方租户内；需要对方可见的部分，由投递服务按字段白名单写入接收方租户的收件表，例如需求投递到每个符合资质的劳务公司，应标投递到用工方，资质材料、账单、分账回执和风险事件投递到平台租户；所有查询仍只读本租户数据
4. ✅ **投递服务是唯一例外点**：只有投递服务可以指定目标租户写入，必须使用字段白名单、保证幂等、记录审计日志；需求变更、撤回、截止等通过事件同步到所有收件方
5. ❌ **禁止**：在业务代码中切换租户上下文；通过租户插件的忽略表配置或原生 SQL 绕过租户插件实现跨组织可见；投递白名单以外的字段（如价差、利润等商业敏感字段）

### 必备字段（强制）

所有表必须包含以下字段：

```sql
id BIGSERIAL PRIMARY KEY,
tenant_id BIGINT NOT NULL,
created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
deleted_at TIMESTAMPTZ
```

### 命名规范（强制）

| 对象 | 规范 | 示例 | 说明 |
|------|------|------|------|
| **表名** | 小写+下划线 | `employee_basic` | 单数形式 |
| **字段名** | 小写+下划线 | `created_at` | 不用驼峰 |
| **索引名** | `idx_字段名` | `idx_tenant_id` | 普通索引 |
| **唯一索引** | `uk_字段名` | `uk_id_card` | 唯一索引 |
| **主键** | `id` | `id` | 统一用id |

### 字段类型约束（强制）

| 数据类型 | 数据库类型 | Java类型 | 说明 |
|---------|-----------|---------|------|
| **主键** | `BIGSERIAL` | `Long` | PostgreSQL自增主键 |
| **金额** | `NUMERIC(10,2)` | `BigDecimal` | **必须用NUMERIC，禁止用DOUBLE PRECISION** |
| **日期时间** | `TIMESTAMPTZ` | `LocalDateTime` | 带时区的时间戳 |
| **布尔** | `BOOLEAN` | `Boolean` | PostgreSQL原生布尔类型 |
| **字符串** | `VARCHAR(N)` | `String` | 根据实际长度设置 |
| **长文本** | `TEXT` | `String` | 超过500字符用TEXT |
| **JSON数据** | `JSONB` | `String` / `JsonNode` | 半结构化数据，支持索引 |

**金额字段的严格要求**：
- ✅ 数据库必须用`NUMERIC(10,2)`
- ✅ Java必须用`BigDecimal`，**禁止用double/float**
- ✅ 前端显示必须格式化为"¥X,XXX.XX"

**原因**：
```java
// ❌ 错误示例（double会有精度问题）
double salary = 2490.5;
double deduction = 100.3;
double result = salary - deduction; // 2390.1999999999998

// ✅ 正确示例（BigDecimal精确）
BigDecimal salary = new BigDecimal("2490.50");
BigDecimal deduction = new BigDecimal("100.30");
BigDecimal result = salary.subtract(deduction); // 2390.20
```

**PostgreSQL金额字段最佳实践**：
```sql
-- ✅ 正确：使用NUMERIC
salary NUMERIC(10,2) NOT NULL,

-- ❌ 错误：使用DOUBLE PRECISION
salary DOUBLE PRECISION NOT NULL  -- 有精度问题

-- ✅ 添加CHECK约束防止负数
ALTER TABLE salary_record ADD CONSTRAINT check_salary_positive 
  CHECK (gross_salary >= 0 AND net_salary >= 0);
```

---

## 🔧 核心能力规范（强制）

### 必须使用项目提供的P0/P1能力

**项目已经实现了P0和P1共18项核心能力，后续开发必须使用这些能力，禁止重复造轮子或自己发明类似功能。**

#### P0核心基础能力（12项）- 必须使用

| 序号 | 能力 | 类名 | 使用场景 | 禁止行为 |
|------|------|------|---------|---------|
| 1 | **统一响应** | `Result`, `PageResult` | 所有Controller返回值 | ❌ 自定义Response类 |
| 2 | **错误码** | `ErrorCode` | 所有异常和错误提示 | ❌ 硬编码错误码和消息 |
| 3 | **业务异常** | `BusinessException` | 业务逻辑抛异常 | ❌ 使用RuntimeException |
| 4 | **全局异常处理** | `GlobalExceptionHandler` | 自动处理，无需手动 | ❌ Controller里try-catch |
| 5 | **租户上下文** | `TenantContext` | 自动处理，无需手动 | ❌ 手动传递tenantId参数 |
| 6 | **MyBatis租户插件** | `MyBatisPlusConfig` | 自动注入WHERE条件 | ❌ SQL手动拼接tenant_id |
| 7 | **RequestId追踪** | `RequestIdFilter` | 自动处理，日志自动包含 | ❌ 自己生成traceId |
| 8 | **日志切面** | `LogAspect` | 自动记录请求日志 | ❌ 手动在Controller写日志 |
| 9 | **金额计算** | `MoneyUtil` | **所有金额计算必须用** | ❌ 用BigDecimal的+、-、*、/ |
| 10 | **日期处理** | `DateUtil` | 所有日期格式化、计算 | ❌ 用SimpleDateFormat |
| 11 | **数据脱敏** | `DesensitizeUtil` | 返回前端的敏感信息 | ❌ 手动截取字符串 |
| 12 | **参数校验** | `ValidationUtil` | 身份证、手机号校验 | ❌ 正则表达式硬编码 |

#### P1常用工具类（6项）- 必须使用

| 序号 | 能力 | 类名 | 使用场景 | 禁止行为 |
|------|------|------|---------|---------|
| 1 | **用户上下文** | `UserContext` | 获取当前登录用户信息 | ❌ 参数传递userId |
| 2 | **ID生成器** | `IdGenerator` | 生成主键ID、业务编号 | ❌ 数据库自增ID、UUID |
| 3 | **加密解密** | `CryptoUtil` | 密码、敏感信息加密 | ❌ 自己写BCrypt、AES |
| 4 | **断言工具** | `AssertUtil` | 参数校验、业务断言 | ❌ if + throw异常 |
| 5 | **字符串工具** | `StringUtil` | 字符串处理、驼峰转换 | ❌ 引入Apache Commons |
| 6 | **集合工具** | `CollectionUtil` | 集合操作、分组、过滤 | ❌ 自己写for循环 |

### 强制使用示例

#### ✅ 正确示例 - 必须这样写

```java
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeMapper employeeMapper;

    @Override
    public Result<EmployeeVO> add(EmployeeAddDTO dto) {
        // 1. 参数校验 - 必须用AssertUtil
        AssertUtil.notEmpty(dto.getName(), "员工姓名不能为空");
        AssertUtil.notEmpty(dto.getIdCard(), "身份证号不能为空");
        AssertUtil.isTrue(ValidationUtil.isValidIdCard(dto.getIdCard()), "身份证号格式不正确");

        // 2. 生成ID和业务编号 - 必须用IdGenerator
        Long id = IdGenerator.nextId();
        String employeeNo = IdGenerator.generateEmployeeNo();

        // 3. 加密敏感信息 - 必须用CryptoUtil
        String encryptedIdCard = CryptoUtil.aesEncrypt(dto.getIdCard());

        // 4. 获取当前用户 - 必须用UserContext
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();

        // 5. 构建实体
        Employee employee = new Employee();
        employee.setId(id);
        employee.setEmployeeNo(employeeNo);
        employee.setName(dto.getName());
        employee.setIdCard(encryptedIdCard);
        employee.setCreatedBy(userId);
        employee.setTenantId(tenantId);

        // 6. 保存
        employeeMapper.insert(employee);

        // 7. 返回 - 必须用Result
        return Result.success(convertToVO(employee));
    }

    @Override
    public Result<PageResult<EmployeeVO>> page(EmployeeQueryDTO dto) {
        // 1. 分页查询
        Page<Employee> page = new Page<>(dto.getPage(), dto.getSize());
        Page<Employee> result = employeeMapper.selectPage(page, null);

        // 2. 转换 - 必须用CollectionUtil
        List<EmployeeVO> voList = CollectionUtil.map(result.getRecords(), this::convertToVO);

        // 3. 返回 - 必须用PageResult
        return Result.success(PageResult.of(result, voList));
    }

    @Override
    public Result<Void> calculateSalary(Long employeeId, String month) {
        // 金额计算 - 必须用MoneyUtil
        BigDecimal grossSalary = new BigDecimal("5000.00");
        BigDecimal deduction = new BigDecimal("500.00");
        BigDecimal netSalary = MoneyUtil.subtract(grossSalary, deduction); // 必须用MoneyUtil

        // 格式化金额 - 必须用MoneyUtil
        String formatted = MoneyUtil.format(netSalary); // ¥4,500.00

        return Result.success();
    }

    private EmployeeVO convertToVO(Employee employee) {
        EmployeeVO vo = new EmployeeVO();
        vo.setId(employee.getId());
        vo.setEmployeeNo(employee.getEmployeeNo());
        vo.setName(employee.getName());
        
        // 解密并脱敏 - 必须用CryptoUtil和DesensitizeUtil
        String idCard = CryptoUtil.aesDecrypt(employee.getIdCard());
        vo.setIdCard(DesensitizeUtil.idCard(idCard));
        
        // 日期格式化 - 必须用DateUtil
        vo.setCreatedAt(DateUtil.format(employee.getCreatedAt()));
        
        return vo;
    }
}
```

#### ❌ 错误示例 - 禁止这样写

```java
@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Override
    public Map<String, Object> add(EmployeeAddDTO dto) {
        // ❌ 错误1：参数校验用if + throw RuntimeException
        if (dto.getName() == null || dto.getName().isEmpty()) {
            throw new RuntimeException("员工姓名不能为空"); // 禁止
        }

        // ❌ 错误2：用UUID生成ID
        String id = UUID.randomUUID().toString(); // 禁止

        // ❌ 错误3：明文存储身份证
        employee.setIdCard(dto.getIdCard()); // 禁止

        // ❌ 错误4：参数传递userId和tenantId
        employeeMapper.insert(employee, userId, tenantId); // 禁止

        // ❌ 错误5：自定义返回格式
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", employee);
        return result; // 禁止
    }

    @Override
    public Map<String, Object> page(EmployeeQueryDTO dto) {
        // ❌ 错误6：自己写for循环转换
        List<EmployeeVO> voList = new ArrayList<>();
        for (Employee employee : employees) {
            voList.add(convertToVO(employee));
        } // 禁止，必须用CollectionUtil.map()

        return result;
    }

    @Override
    public void calculateSalary(Long employeeId, String month) {
        // ❌ 错误7：用double计算金额
        double grossSalary = 5000.0;
        double deduction = 500.0;
        double netSalary = grossSalary - deduction; // 禁止

        // ❌ 错误8：直接用BigDecimal的+、-、*、/
        BigDecimal result = grossSalary.subtract(deduction); // 禁止，必须用MoneyUtil
    }

    private EmployeeVO convertToVO(Employee employee) {
        EmployeeVO vo = new EmployeeVO();
        
        // ❌ 错误9：手动截取字符串脱敏
        String idCard = employee.getIdCard();
        String masked = idCard.substring(0, 3) + "***" + idCard.substring(idCard.length() - 4);
        vo.setIdCard(masked); // 禁止，必须用DesensitizeUtil
        
        // ❌ 错误10：用SimpleDateFormat格式化日期
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        vo.setCreatedAt(sdf.format(employee.getCreatedAt())); // 禁止，必须用DateUtil
        
        return vo;
    }
}
```

### 违反规范的后果

1. **Code Review不通过** - 代码审查时会被打回重写
2. **无法通过CheckStyle检查** - CI/CD会自动检查并拒绝合并
3. **单元测试失败** - 不符合规范的代码会导致测试失败
4. **生产事故** - 如用double计算金额，会导致财务数据错误

### 如何检查是否遵守规范

```bash
# 1. 运行CheckStyle检查
mvn checkstyle:check

# 2. 运行单元测试
mvn test

# 3. 查看代码规范文档
# 详见：backend/P0_IMPLEMENTATION_COMPLETE.md
# 详见：backend/P1_IMPLEMENTATION_COMPLETE.md
```

---

## 💻 代码规范（强制）

### Java代码规范

**基础规范**：
- 遵循《阿里巴巴Java开发手册》
- 使用Lombok减少模板代码
- 使用CheckStyle静态检查

**分层架构（强制）**：
```
Controller层：只做参数校验和转发，不写业务逻辑
   ↓
Service层：写业务逻辑，不直接操作数据库
   ↓
Mapper层：数据库操作，不写业务逻辑
```

**禁止的写法**：
```java
// ❌ 禁止：Controller层直接操作数据库
@RestController
public class EmployeeController {
    @Autowired
    private EmployeeMapper employeeMapper; // 禁止
    
    @GetMapping("/employee/{id}")
    public Employee get(@PathVariable Long id) {
        return employeeMapper.selectById(id); // 禁止
    }
}

// ✅ 正确：Controller层调用Service层
@RestController
public class EmployeeController {
    @Autowired
    private EmployeeService employeeService; // 正确
    
    @GetMapping("/employee/{id}")
    public Employee get(@PathVariable Long id) {
        return employeeService.getById(id); // 正确
    }
}
```

### 命名规范（强制）

| 对象 | 规范 | 示例 |
|------|------|------|
| **类名** | 大驼峰（PascalCase） | `EmployeeService` |
| **方法名** | 小驼峰（camelCase） | `getEmployeeById` |
| **变量名** | 小驼峰（camelCase） | `employeeName` |
| **常量** | 全大写+下划线 | `MAX_RETRY_COUNT` |
| **包名** | 全小写 | `com.company.labor` |

### 注释规范（强制）

**必须写注释的地方**：
1. ✅ 所有public方法必须有JavaDoc注释
2. ✅ 所有复杂业务逻辑必须有行内注释
3. ✅ 所有魔法数字必须用常量并注释

```java
/**
 * 计算员工工资
 * 
 * @param employeeId 员工ID
 * @param month 月份（格式：YYYY-MM）
 * @return 工资详情
 */
public SalaryDetail calculateSalary(Long employeeId, String month) {
    // 获取工资政策
    SalaryPolicy policy = policyService.getByEmployeeId(employeeId);
    
    // 获取工时数据
    WorkhourSummary workhour = workhourService.getSummary(employeeId, month);
    
    // 计算应发工资 = 底薪 + 正常工时工资 + 加班工资
    BigDecimal baseSalary = policy.getBaseSalary();
    BigDecimal normalPay = workhour.getNormalHours().multiply(policy.getHourlyRate());
    BigDecimal overtimePay = workhour.getOvertimeHours().multiply(policy.getOvertimeRate());
    
    // 计算实发工资 = 应发工资 - 扣款 - 借支
    BigDecimal grossSalary = baseSalary.add(normalPay).add(overtimePay);
    BigDecimal deduction = calculateDeduction(employeeId, month);
    BigDecimal advanceDeduction = getAdvanceDeduction(employeeId, month);
    BigDecimal netSalary = grossSalary.subtract(deduction).subtract(advanceDeduction);
    
    return new SalaryDetail(grossSalary, netSalary, ...);
}
```

---

## 🔐 安全规范（强制）

### SQL注入防护（强制）

**禁止拼接SQL**：
```java
// ❌ 禁止：字符串拼接SQL
String sql = "SELECT * FROM employee WHERE name = '" + name + "'"; // 有SQL注入风险

// ✅ 正确：使用参数化查询
@Select("SELECT * FROM employee WHERE name = #{name}")
Employee getByName(@Param("name") String name);
```

### 密码加密（强制）

**禁止明文存储密码**：
```java
// ❌ 禁止：明文存储
user.setPassword(rawPassword); // 禁止

// ✅ 正确：BCrypt加密
String encodedPassword = passwordEncoder.encode(rawPassword);
user.setPassword(encodedPassword);
```

### 数据脱敏（强制）

**敏感数据必须脱敏**：
```java
// 身份证：显示前3位和后4位，中间用*代替
// 330123199001011234 -> 330***********1234

// 手机号：显示前3位和后4位
// 13812345678 -> 138****5678

// 银行卡：显示后4位
// 6217001234567890123 -> ***************0123
```

### 日志安全（强制）

**禁止在日志中输出敏感信息**：
```java
// ❌ 禁止：日志中输出密码、身份证、银行卡
log.info("用户登录：{}", user); // user中可能包含密码

// ✅ 正确：只输出必要信息，敏感信息脱敏
log.info("用户登录：userId={}, username={}", user.getId(), user.getUsername());
```

---

## 🎨 前端规范（强制）

### Vue 3代码规范

**组件命名**：
```javascript
// ✅ 正确：大驼峰
EmployeeList.vue
EmployeeDetail.vue

// ❌ 错误：小写或下划线
employee-list.vue
employee_list.vue
```

**变量命名**：
```javascript
// ✅ 正确：小驼峰
const employeeName = 'xxx'
const maxRetryCount = 3

// ❌ 错误：其他形式
const EmployeeName = 'xxx'
const max_retry_count = 3
```

### 接口调用规范（强制）

**统一使用axios封装的API**：
```javascript
// ✅ 正确：使用封装的API
import { getEmployeeList } from '@/api/employee'

const fetchData = async () => {
  const res = await getEmployeeList({ page: 1, size: 20 })
  // 处理数据
}

// ❌ 错误：直接用axios
axios.get('/api/employee/list?page=1&size=20')
```

---

## 🌐 API接口规范（强制）

### RESTful风格（强制）

```
GET    /api/employees          # 查询员工列表
GET    /api/employees/{id}     # 查询员工详情
POST   /api/employees          # 新增员工
PUT    /api/employees/{id}     # 修改员工
DELETE /api/employees/{id}     # 删除员工
```

### 统一响应格式（强制）

**成功响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1697520000000
}
```

**失败响应**：
```json
{
  "code": 400,
  "message": "参数错误：员工姓名不能为空",
  "data": null,
  "timestamp": 1697520000000
}
```

### 错误码规范（强制）

| 错误码 | 说明 | 何时使用 |
|--------|------|---------|
| **200** | 成功 | 请求成功 |
| **400** | 参数错误 | 客户端参数不合法 |
| **401** | 未登录 | 用户未登录或token过期 |
| **403** | 无权限 | 用户无权访问该资源 |
| **404** | 资源不存在 | 请求的资源不存在 |
| **500** | 服务器错误 | 服务器内部错误 |

---

## 🧪 测试规范（强制）

### 单元测试覆盖率（强制）

- ✅ 核心业务逻辑必须有单元测试
- ✅ 覆盖率目标：≥80%
- ✅ 使用JUnit 5 + Mockito

**必须测试的场景**：
1. 工资计算逻辑
2. 返费计算逻辑
3. 撞单判断逻辑
4. 达标判断逻辑

### 测试用例编写规范

```java
@Test
@DisplayName("工资计算：正常工时 + 加班工时")
public void testCalculateSalary_NormalAndOvertime() {
    // Given（准备数据）
    Long employeeId = 1L;
    String month = "2024-10";
    
    // When（执行操作）
    SalaryDetail result = salaryService.calculateSalary(employeeId, month);
    
    // Then（验证结果）
    assertEquals(new BigDecimal("5500.00"), result.getGrossSalary());
    assertEquals(new BigDecimal("5000.00"), result.getNetSalary());
}
```

---

## 📦 Git规范（强制）

### 分支管理

```
main          # 主分支（生产环境）
develop       # 开发分支（测试环境）
feature/xxx   # 功能分支（开发中）
hotfix/xxx    # 紧急修复分支
```

### Commit规范

```
feat: 新增员工管理模块
fix: 修复工资计算精度问题
docs: 更新API文档
style: 代码格式化
refactor: 重构返费计算逻辑
test: 增加单元测试
chore: 更新依赖版本
```

**Commit消息格式**：
```
<type>: <subject>

<body>

<footer>
```

**示例**：
```
fix: 修复工资计算中借支扣款未生效的问题

问题描述：
发工资时，借支金额未从工资中扣除

修复方案：
在工资计算时，调用getAdvanceDeduction()获取借支金额并扣除

影响范围：
SalaryService.calculateSalary()方法

测试情况：
已通过单元测试和集成测试

Closes #123
```

---

## 🚫 严格禁止事项

### 代码层面

❌ **绝对禁止**：
1. 在Controller层写业务逻辑
2. 使用double/float存储金额
3. SQL语句拼接（必须用参数化查询）
4. 密码明文存储
5. 在日志中输出密码、身份证、银行卡
6. 硬编码（魔法数字、魔法字符串）
7. 捕获异常后不处理（空catch块）
8. 返回null（应返回Optional或空集合）

### 数据库层面

❌ **绝对禁止**：
1. 直接操作生产数据库（必须通过应用）
2. 删除业务表的`tenant_id`字段
3. 不加WHERE条件的UPDATE/DELETE
4. 使用SELECT * （必须指定字段）
5. 不加索引的查询（超过1000条记录）

### Git层面

❌ **绝对禁止**：
1. 直接push到main分支
2. 强制push（git push -f）到共享分支
3. Commit包含密码、密钥等敏感信息
4. Commit消息写"修复bug"、"test"等无意义内容

---

## ⚡ 性能规范（强制）

### 查询优化

**必须加索引的字段**：
1. `tenant_id`（所有业务表）
2. `created_at`（时间范围查询）
3. `status`（状态查询）
4. 外键字段
5. 频繁作为WHERE条件的字段

**N+1查询问题**：
```java
// ❌ 错误：N+1查询
List<Employee> employees = employeeMapper.selectAll();
for (Employee emp : employees) {
    Project project = projectMapper.selectById(emp.getProjectId()); // N次查询
}

// ✅ 正确：使用JOIN或批量查询
List<EmployeeVO> employees = employeeMapper.selectAllWithProject(); // 1次查询
```

### 缓存策略

**必须缓存的数据**：
1. 字典数据（数据字典、配置项）
2. 用户权限信息
3. 热点数据（经常查询的数据）

**缓存Key命名规范**：
```
{业务模块}:{数据类型}:{ID}

示例：
employee:basic:1001
salary:policy:1001
dict:position:all
```

---

## 📊 监控与日志规范（强制）

### 日志级别

| 级别 | 使用场景 |
|------|---------|
| **ERROR** | 系统错误、异常 |
| **WARN** | 警告信息（如缓存未命中、接口超时） |
| **INFO** | 关键业务节点（如登录、下单、支付） |
| **DEBUG** | 调试信息（开发环境） |

### 日志格式

```java
// ✅ 正确：结构化日志
log.info("员工入职：employeeId={}, name={}, projectId={}", 
    employeeId, name, projectId);

// ❌ 错误：非结构化日志
log.info("员工" + name + "入职了");
```

### 监控指标

**必须监控的指标**：
1. API响应时间（P50、P95、P99）
2. 数据库慢查询（>1秒）
3. 缓存命中率
4. 系统错误率
5. 并发用户数

---

## 🔄 数据一致性规范（强制）

### 事务管理

**必须使用事务的场景**：
1. 工资发放（工资表 + 借支扣款）
2. 员工离职（员工状态 + 借支结算 + 返费判定）
3. 返费结算（返费记录 + 渠道账户）

```java
@Transactional(rollbackFor = Exception.class)
public void releaseSalary(Long employeeId, String month) {
    // 1. 生成工资记录
    salaryMapper.insert(salary);
    
    // 2. 扣除借支
    advanceService.deduct(employeeId, month);
    
    // 3. 发放记录
    paymentMapper.insert(payment);
}
```

### 分布式锁

**必须使用分布式锁的场景**：
1. 防止重复报名（撞单检查）
2. 防止重复发放工资
3. 防止重复结算返费

```java
String lockKey = "employee:enroll:" + idCard;
if (redisLock.tryLock(lockKey, 10, TimeUnit.SECONDS)) {
    try {
        // 检查是否已报名
        // 插入报名记录
    } finally {
        redisLock.unlock(lockKey);
    }
}
```

---

## 📝 文档规范（强制）

### 必须编写的文档

1. ✅ API接口文档（Swagger自动生成）
2. ✅ 数据库设计文档（ER图、表结构）
3. ✅ 部署文档（环境配置、部署步骤）
4. ✅ 核心业务逻辑文档（工资计算、返费计算）

### 文档更新规则

- 代码变更后，必须同步更新文档
- 接口变更必须通知前端
- 数据库变更必须记录变更日志

---

## 🎯 核心技术约束总结

### 金额计算（核心红线）

✅ **必须**：
- 数据库用`NUMERIC(10,2)`
- Java用`BigDecimal`
- 计算时用`add()`、`subtract()`、`multiply()`、`divide()`
- 前端显示格式化为"¥X,XXX.XX"

❌ **禁止**：
- 用double/float或DOUBLE PRECISION
- 用字符串计算
- 直接用+、-、*、/运算符

### 多租户隔离（核心红线）

✅ **必须**：
- 所有业务表有`tenant_id`字段
- 所有查询自动注入`WHERE tenant_id = ?`
- 使用MyBatis Plus租户插件
- 100%保证数据隔离

❌ **禁止**：
- 跨租户查询
- 手动拼接`tenant_id`条件
- 绕过租户插件

---

**最后更新**：2026年10月19日  
**维护人**：技术负责人  
**强制等级**：所有约束必须100%遵守
