# 后端项目

劳务中介管理系统 - 后端服务

## 📋 技术栈

- **语言**：Java 17
- **框架**：Spring Boot 3.2+
- **ORM**：MyBatis Plus 3.5+
- **数据库**：MySQL 8.0+
- **缓存**：Redis 7.0+
- **消息队列**：RabbitMQ 3.12+

## 🏗️ 项目结构

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/company/labor/
│   │   │   ├── module/              # 业务模块
│   │   │   │   ├── employee/        # 员工管理
│   │   │   │   ├── salary/          # 工资管理
│   │   │   │   ├── rebate/          # 返费结算
│   │   │   │   └── ...
│   │   │   └── common/              # 公共模块
│   │   │       ├── config/          # 配置
│   │   │       ├── util/            # 工具类
│   │   │       ├── exception/       # 异常
│   │   │       └── constant/        # 常量
│   │   └── resources/
│   │       ├── application.yml      # 主配置
│   │       ├── application-dev.yml  # 开发环境
│   │       ├── application-test.yml # 测试环境
│   │       └── application-prod.yml # 生产环境
│   └── test/                        # 测试代码
├── pom.xml                          # Maven配置
└── README.md                        # 本文件
```

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 7.0+

### 本地开发

1. **克隆项目**
```bash
git clone <repository-url>
cd LaborAntermediaries/backend
```

2. **配置数据库**
```sql
CREATE DATABASE labor_intermediaries DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

3. **执行数据库初始化脚本**
```bash
mysql -u root -p labor_intermediaries < ../database/schema/V1.0.0__init.sql
```

4. **修改配置文件**

编辑 `src/main/resources/application-dev.yml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/labor_intermediaries
    username: root
    password: your_password
  redis:
    host: localhost
    port: 6379
```

5. **启动应用**
```bash
mvn spring-boot:run
```

6. **访问接口文档**

浏览器打开：http://localhost:8080/doc.html

## 🧪 运行测试

```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=EmployeeServiceTest

# 生成测试覆盖率报告
mvn jacoco:report
```

## 📦 构建部署

```bash
# 打包（跳过测试）
mvn clean package -DskipTests

# 打包（包含测试）
mvn clean package

# 生成的jar文件在：target/labor-backend-*.jar
```

## 🔐 核心约束

### 多租户设计（强制）

所有业务表必须包含 `tenant_id` 字段：

```java
@TableName("employee_basic")
public class Employee extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;  // 租户ID（必须）
    
    // 其他字段...
}
```

### 金额计算（强制）

金额必须使用 `BigDecimal`，禁止使用 `double` 或 `float`：

```java
// ✅ 正确
BigDecimal salary = new BigDecimal("5500.00");
BigDecimal deduction = new BigDecimal("500.00");
BigDecimal result = salary.subtract(deduction);

// ❌ 错误
double salary = 5500.0;
double result = salary - 500.0;  // 有精度问题
```

### 分层架构（强制）

严格遵循三层架构：

```
Controller → Service → Mapper
```

- Controller层：只做参数校验和转发
- Service层：业务逻辑
- Mapper层：数据库操作

## 📚 相关文档

- [技术规范](../CLAUDE.md)
- [工程治理](../GOVERNANCE.md)
- [API文档](http://localhost:8080/doc.html)
- [数据库设计](../database/docs/)

## 🤝 贡献

请参考 [贡献指南](../CONTRIBUTING.md)

## 📝 变更日志

请参考 [CHANGELOG.md](../CHANGELOG.md)
