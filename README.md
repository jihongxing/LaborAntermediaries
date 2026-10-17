# 劳务中介管理SaaS系统

一个基于Spring Boot 3.2 + Vue 3 + PostgreSQL的多租户劳务中介管理系统。

## 🚀 快速开始

```bash
# 1. 克隆项目
git clone https://github.com/your-org/LaborAntermediaries.git

# 2. 阅读技术规范（必读）
# 详见 CLAUDE.md

# 3. 启动后端
cd backend
mvn spring-boot:run

# 4. 启动前端
cd frontend/web-admin
npm install
npm run dev
```

## 📚 文档导航

### 核心文档（必读）
- [CLAUDE.md](CLAUDE.md) - **技术规范总纲**（必读第一文档）
- [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - 快速参考卡片（贴在显示器旁边）

### 开发文档
- [backend/README.md](backend/README.md) - 后端项目说明
- [docs/development/getting-started.md](docs/development/getting-started.md) - 快速开始
- [docs/development/troubleshooting.md](docs/development/troubleshooting.md) - 常见问题

### 架构文档
- [docs/architecture/system-design.md](docs/architecture/system-design.md) - 系统架构设计
- [docs/architecture/database-design.md](docs/architecture/database-design.md) - 数据库设计

### 管理文档
- [DOCUMENTATION_GOVERNANCE.md](DOCUMENTATION_GOVERNANCE.md) - 文档管理规范
- [CHANGELOG.md](CHANGELOG.md) - 版本变更日志
- [CONTRIBUTING.md](CONTRIBUTING.md) - 贡献指南

## 🏗️ 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| **后端** | Spring Boot | 3.2+ |
| **ORM** | MyBatis Plus | 3.5+ |
| **数据库** | PostgreSQL | 16+ |
| **缓存** | Redis | 7.0+ |
| **消息队列** | RabbitMQ | 3.12+ |
| **前端** | Vue 3 + TypeScript | 3.x |
| **UI组件** | Element Plus | 2.x |
| **移动端** | Flutter | 3.x |

## 📦 项目结构

```
LaborAntermediaries/
├── backend/           # 后端（Spring Boot）
│   ├── src/
│   ├── pom.xml
│   └── README.md
├── frontend/          # 前端
│   ├── web-admin/    # Web管理后台（Vue 3）
│   ├── mobile-app/   # 移动App（Flutter）
│   └── mini-program/ # 微信小程序（uni-app）
├── database/          # 数据库脚本
├── docs/              # 文档
└── scripts/           # 脚本
```

## 🔧 开发规范

### 代码规范

所有开发人员和AI助手必须严格遵守 [CLAUDE.md](CLAUDE.md) 中的规范：

1. **分层架构**：Controller → Service → Mapper
2. **多租户隔离**：所有表必须有`tenant_id`
3. **金额计算**：必须用`BigDecimal` + `MoneyUtil`
4. **安全规范**：防SQL注入、密码加密、数据脱敏
5. **P0/P1能力**：必须使用项目提供的18项核心能力

### 开发流程

```bash
# 1. 创建功能分支
git checkout -b feature/employee-management

# 2. 开发（遵守CLAUDE.md规范）
# 参考 QUICK_REFERENCE.md

# 3. 运行检查
mvn checkstyle:check
mvn test

# 4. 提交代码
git add .
git commit -m "feat: 新增员工管理模块"

# 5. 发起Pull Request
# 需要Code Review评分≥90分
```

## 🎯 核心功能

- ✅ **员工管理**：入职、离职、档案管理
- ✅ **工资核算**：工资计算、借支管理、工资发放
- ✅ **返费管理**：返费计算、达标判定、结算支付
- ✅ **撞单检查**：身份证号查重、跨租户检查
- ✅ **多租户支持**：数据隔离、租户管理
- ✅ **权限管理**：RBAC权限、数据权限

## 🔐 安全特性

- ✅ **多租户数据隔离**：MyBatis Plus租户插件
- ✅ **SQL注入防护**：参数化查询
- ✅ **密码加密存储**：BCrypt加密
- ✅ **敏感信息加密**：AES加密（身份证、银行卡）
- ✅ **数据脱敏展示**：身份证、手机号、银行卡
- ✅ **请求追踪**：RequestId全链路追踪

## 📊 性能优化

- ✅ **数据库索引**：tenant_id、外键、状态字段
- ✅ **Redis缓存**：字典数据、用户权限、热点数据
- ✅ **分页查询**：MyBatis Plus分页插件
- ✅ **连接池**：HikariCP连接池
- ✅ **异步处理**：RabbitMQ消息队列

## 🧪 测试

```bash
# 运行单元测试
mvn test

# 运行集成测试
mvn verify

# 查看测试覆盖率
mvn jacoco:report
```

## 📈 监控与日志

- ✅ **请求日志**：LogAspect自动记录
- ✅ **异常日志**：全局异常处理
- ✅ **RequestId**：全链路追踪
- ✅ **慢查询监控**：>1秒自动告警

## 🤝 贡献指南

详见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 📄 许可证

Copyright © 2024 Your Company. All rights reserved.

---

**重要提示**：开发前必读 [CLAUDE.md](CLAUDE.md)
