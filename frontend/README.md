# 前端项目

劳务中介管理系统 - 前端应用（三端）

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
