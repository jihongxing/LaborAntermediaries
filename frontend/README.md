# 前端项目

劳务供应链交易平台的三端工程预留与开发参考。

## 目录职责与现状

当前各端仅有说明或静态检查配置，没有完整依赖清单、业务源码与可运行工程；下文技术栈、功能和命令属于早期规划参考，不是已实现能力或当前可执行步骤。

- [web-admin/](web-admin/README.md)：Web 管理端预留目录。
- [mobile-app/](mobile-app/README.md)：独立移动 App 预留目录。
- [mini-program/](mini-program/README.md)：微信小程序预留目录。
- 必须先对齐 [当前 PRD](../docs/02-开发阶段/产品设计/PRD-v0.1.md)，经技术评审确定工程与依赖版本，再提供可复现的安装、构建和测试步骤。
- 跨端共识引用权威需求文档，接口遵守后端认证、错误码与组织上下文契约。

## 禁止事项

- 禁止将旧 SaaS 功能列表视为当前交付范围，或因某端方便实现而擅自缩减其他端范围。
- 禁止提交真实 Token、生产配置和用户资料；前端权限隐藏不得替代服务端授权。
- 禁止在客户端固化待审奖励费率、支付资格或以界面展示作出无条件收益承诺。

## 📋 项目概述

本项目包含三个前端应用：

1. **Web管理后台**：Vue 3 + Element Plus（管理员、财务、项目经理使用）
2. **移动App**：Flutter（驻场人员、项目经理使用）
3. **微信小程序**：uni-app（经纪人、员工使用）

## 📂 目录结构

```
frontend/
├── web-admin/          # Web管理后台
├── mobile-app/         # 移动App (Flutter)
└── mini-program/       # 微信小程序 (uni-app)
```

## 🔗 快速链接

- [Web管理后台开发指南](./web-admin/README.md)
- [移动App开发指南](./mobile-app/README.md)
- [微信小程序开发指南](./mini-program/README.md)

## 🎨 设计规范

### 通用规范

- **命名规范**：组件使用大驼峰（PascalCase），变量使用小驼峰（camelCase）
- **代码风格**：遵循ESLint配置
- **注释规范**：重要逻辑必须添加注释

### 接口调用规范

所有端统一使用RESTful API：

```javascript
// 基础URL
const API_BASE_URL = 'https://api.example.com'

// 统一响应格式
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1697520000000
}
```

## 🚀 快速开始

### 前置要求

- Node.js 18+
- Flutter 3.x（移动App）
- 微信开发者工具（小程序）

### 本地开发

每个子项目都有独立的README，请参考对应目录。

## 📚 相关文档

- [技术规范](../CLAUDE.md)
- [工程治理](../GOVERNANCE.md)
- [贡献指南](../CONTRIBUTING.md)

## 🤝 贡献

请参考 [贡献指南](../CONTRIBUTING.md)
