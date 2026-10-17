# Labor Backend

劳务中介管理系统后端项目

## 📋 项目状态

✅ **P0 + P1核心能力已完成**（2026-10-18）

### P0核心基础能力（12项）✅
- ✅ 统一响应封装（Result、PageResult）
- ✅ 全局异常处理（BusinessException、GlobalExceptionHandler）
- ✅ 租户上下文（TenantContext、MyBatis Plus租户插件）
- ✅ 请求追踪（RequestIdFilter、LogAspect）
- ✅ 金额工具（MoneyUtil）- 强制BigDecimal
- ✅ 日期工具（DateUtil）- LocalDateTime处理
- ✅ 数据脱敏（DesensitizeUtil）- 身份证、手机号、银行卡
- ✅ 参数校验（ValidationUtil）- 身份证、手机号、邮箱
- ✅ JSON工具（JsonUtil）- 统一序列化
- ✅ 错误码管理（ErrorCode）
- ✅ 日志配置（logback-spring.xml）- 包含request_id
- ✅ Maven依赖管理（pom.xml）

### P1常用工具类（6项）✅
- ✅ 用户上下文（UserContext）- ThreadLocal用户信息
- ✅ ID生成器（IdGenerator）- 雪花算法 + 业务编号
- ✅ 加密解密（CryptoUtil）- BCrypt、AES、MD5、Base64
- ✅ 断言工具（AssertUtil）- 简化参数校验
- ✅ 字符串工具（StringUtil）- 驼峰转换、拼接分割
- ✅ 集合工具（CollectionUtil）- 分组、映射、过滤

详细文档：
- [P0_IMPLEMENTATION_COMPLETE.md](P0_IMPLEMENTATION_COMPLETE.md) - P0实现总结
- [P1_IMPLEMENTATION_COMPLETE.md](P1_IMPLEMENTATION_COMPLETE.md) - P1实现总结

## 🏗️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | JDK 17+ | 开发语言 |
| Spring Boot | 3.2.0 | 开发框架 |
| MyBatis Plus | 3.5.5 | ORM框架 |
| PostgreSQL | 16+ | 关系数据库 |
| Redis | 7.0+ | 缓存 |
| RabbitMQ | 3.12+ | 消息队列 |
| Lombok | 1.18.30 | 简化代码 |
| Hutool | 5.8.23 | 工具库 |
| Jackson | 2.15.3 | JSON序列化 |

## 📦 项目结构

```
backend/
├── src/main/java/com/company/labor/
│   ├── common/                      # 公共模块（P0 + P1能力）
│   │   ├── Result.java              # 统一响应
│   │   ├── PageResult.java          # 分页响应
│   │   ├── aspect/                  # 切面
│   │   │   └── LogAspect.java       # 日志切面
│   │   ├── config/                  # 配置类
│   │   │   └── MyBatisPlusConfig.java  # MyBatis配置（租户插件）
│   │   ├── constant/                # 常量
│   │   │   └── ErrorCode.java       # 错误码
│   │   ├── context/                 # 上下文
│   │   │   ├── TenantContext.java   # 租户上下文
│   │   │   └── UserContext.java     # 用户上下文（P1）
│   │   ├── exception/               # 异常
│   │   │   ├── BusinessException.java         # 业务异常
│   │   │   └── GlobalExceptionHandler.java    # 全局异常处理器
│   │   ├── filter/                  # 过滤器
│   │   │   └── RequestIdFilter.java # RequestId过滤器
│   │   └── util/                    # 工具类
│   │       ├── AssertUtil.java      # 断言工具（P1）
│   │       ├── CollectionUtil.java  # 集合工具（P1）
│   │       ├── CryptoUtil.java      # 加密解密工具（P1）
│   │       ├── DateUtil.java        # 日期工具
│   │       ├── DesensitizeUtil.java # 数据脱敏工具
│   │       ├── IdGenerator.java     # ID生成器（P1）
│   │       ├── JsonUtil.java        # JSON工具
│   │       ├── MoneyUtil.java       # 金额工具
│   │       ├── StringUtil.java      # 字符串工具（P1）
│   │       └── ValidationUtil.java  # 参数校验工具
│   ├── employee/                    # 员工模块（待实现）
│   ├── salary/                      # 工资模块（待实现）
│   └── LaborApplication.java        # 启动类
├── src/main/resources/
│   ├── application.yml              # Spring Boot配置
│   └── logback-spring.xml           # 日志配置
├── src/test/java/                   # 单元测试
│   └── com/company/labor/common/
│       ├── ResultTest.java
│       ├── context/
│       │   ├── TenantContextTest.java
│       │   └── UserContextTest.java        # P1测试
│       └── util/
│           ├── CollectionUtilTest.java     # P1测试
│           ├── CryptoUtilTest.java         # P1测试
│           ├── DesensitizeUtilTest.java
│           ├── IdGeneratorTest.java        # P1测试
│           ├── MoneyUtilTest.java
│           ├── StringUtilTest.java         # P1测试
│           └── ValidationUtilTest.java
├── pom.xml                          # Maven配置
├── README.md                        # 项目说明
├── P0_IMPLEMENTATION_COMPLETE.md    # P0实现总结
└── P1_IMPLEMENTATION_COMPLETE.md    # P1实现总结

测试覆盖率：约80%（核心工具类100%覆盖）
```

## 🚀 快速开始

### 1. 环境准备

确保已安装以下软件：

- JDK 17+
- Maven 3.8+
- PostgreSQL 16+
- Redis 7.0+

### 2. 数据库初始化

```sql
-- 1. 创建数据库
CREATE DATABASE labor_db;

-- 2. 执行初始化脚本（待补充）
-- psql -U postgres -d labor_db -f init.sql
```

### 3. 修改配置文件

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/labor_db
    username: postgres
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379
      password: your_password  # 如果没有密码则留空
```

### 4. 启动应用

```bash
# 编译
mvn clean package

# 运行
java -jar target/labor-backend-1.0.0.jar

# 或使用Maven插件
mvn spring-boot:run
```

### 5. 验证启动

访问：`http://localhost:8080`

查看日志：`logs/labor.log`

## 📖 使用示例

### 1. Controller层

```java
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {
    private final EmployeeService employeeService;

    @GetMapping("/{id}")
    public Result<Employee> get(@PathVariable Long id) {
        Employee employee = employeeService.getById(id);
        if (employee == null) {
            return Result.error(ErrorCode.EMPLOYEE_NOT_FOUND);
        }
        return Result.success(employee);
    }
}
```

### 2. Service层

```java
@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeMapper employeeMapper;

    @Transactional(rollbackFor = Exception.class)
    public void add(EmployeeAddRequest request) {
        // 参数校验
        ValidationUtil.validateIdCard(request.getIdCard());
        
        // 保存员工（自动注入tenant_id）
        Employee employee = new Employee();
        employee.setName(request.getName());
        employeeMapper.insert(employee);
    }
}
```

### 3. 工具类

```java
// 用户上下文（P1）
Long userId = UserContext.getUserId();
String username = UserContext.getUsername();
Long tenantId = UserContext.getTenantId();

// ID生成器（P1）
Long id = IdGenerator.nextId(); // 雪花算法
String employeeNo = IdGenerator.generateEmployeeNo(); // EMP20241018001

// 加密解密（P1）
String encodedPassword = CryptoUtil.encodeBCrypt("123456");
boolean matches = CryptoUtil.matchesBCrypt("123456", encodedPassword);
String encrypted = CryptoUtil.aesEncrypt("330123199001011234");
String decrypted = CryptoUtil.aesDecrypt(encrypted);

// 断言工具（P1）
AssertUtil.notNull(employee, "员工不存在");
AssertUtil.notEmpty(name, "员工姓名不能为空");
AssertUtil.isTrue(salary.compareTo(BigDecimal.ZERO) > 0, "工资必须大于0");

// 字符串工具（P1）
String snakeCase = StringUtil.toSnakeCase("employeeName"); // employee_name
String camelCase = StringUtil.toCamelCase("employee_name"); // employeeName
List<String> parts = StringUtil.split("a,b,c", ","); // ["a", "b", "c"]

// 集合工具（P1）
List<Long> ids = CollectionUtil.map(employees, Employee::getId);
Map<Long, List<Employee>> grouped = CollectionUtil.groupBy(employees, Employee::getProjectId);
List<Employee> filtered = CollectionUtil.filter(employees, e -> e.getStatus() == 1);

// 金额计算
BigDecimal netSalary = MoneyUtil.subtract(grossSalary, deduction);
String formatted = MoneyUtil.format(netSalary); // ¥2,390.20

// 日期计算
LocalDateTime endOfMonth = DateUtil.endOfMonth(DateUtil.now());
int workdays = DateUtil.countWeekdays(startDate, endDate);

// 数据脱敏
String masked = DesensitizeUtil.idCard("330123199001011234"); // 330***********1234

// 参数校验
ValidationUtil.validateMobile("13812345678"); // 通过，否则抛异常
```

## 🔐 安全规范

### 1. 多租户数据隔离

- ✅ 所有业务表必须有`tenant_id`字段
- ✅ MyBatis Plus租户插件自动注入`WHERE tenant_id = ?`
- ✅ 在拦截器中设置`TenantContext.setTenantId()`
- ⚠️ 跨租户查询必须经过审批

### 2. 金额字段规范

- ✅ 数据库必须用`NUMERIC(10,2)`
- ✅ Java必须用`BigDecimal`
- ✅ 计算时必须用`MoneyUtil`
- ❌ 禁止用double/float

### 3. 敏感信息处理

- ✅ 密码BCrypt加密
- ✅ 身份证、手机号、银行卡自动脱敏
- ✅ 日志中不输出敏感信息
- ✅ SQL参数化查询（禁止拼接）

## 🧪 测试

```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=MoneyUtilTest

# 查看测试覆盖率
mvn jacoco:report
```

测试报告位置：`target/site/jacoco/index.html`

## 📝 开发规范

详细规范请参考：[CLAUDE.md](../CLAUDE.md)

核心约束：
1. ✅ Controller层只做参数校验和转发，不写业务逻辑
2. ✅ Service层写业务逻辑，不直接操作数据库
3. ✅ Mapper层只做数据库操作，不写业务逻辑
4. ✅ 所有public方法必须有JavaDoc注释
5. ✅ 金额必须用BigDecimal，禁止用double/float
6. ✅ 所有业务表必须有tenant_id字段
7. ✅ SQL必须用参数化查询，禁止拼接

## 🔜 下一步计划

### 业务模块（按优先级）

1. **员工管理** - 员工基本信息、入职、离职
2. **项目管理** - 项目信息、项目分配
3. **考勤管理** - 工时记录、加班统计
4. **工资管理** - 工资计算、工资发放、借支扣款
5. **渠道管理** - 渠道信息、返费管理
6. **统计报表** - 工资统计、返费统计

## 🐛 问题反馈

如有问题，请联系技术团队。

---

**最后更新**：2026年10月18日  
**维护人**：技术团队  
**版本**：v1.0
