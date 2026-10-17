# 变更日志

本文档记录项目的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 新增
- 初始化项目结构
- 完成售前阶段全部文档
- 建立工程治理体系

---

## [0.1.0] - 2026-10-17

### 新增

#### 文档体系
- 创建项目文档结构（docs/00-05五个阶段）
- 完成需求文档v0.2（15个模块详细说明）
- 完成报价方案v2.0（¥140万，682人天）
- 完成客户交付包（6份Markdown + 6份Word）
- 完成合同准备文档（5个附件）
- 完成技术交接文档（给开发团队）

#### 治理文档
- 创建CLAUDE.md（技术规范与强制约束）
- 创建IDEA.md（项目创意与核心概念）
- 创建GOVERNANCE.md（工程治理方案）
- 创建CHANGELOG.md（本文件）
- 创建CONTRIBUTING.md（贡献指南）

#### 配置文件
- 创建.gitignore（Git忽略文件）
- 创建.editorconfig（编辑器配置）
- 创建GitHub Actions CI/CD配置

#### 项目结构
- 建立Monorepo目录结构
- 创建backend/、frontend/、database/、infrastructure/等目录

### 核心数据（统一版）
- 人天单价：¥1,500/人天
- 项目总价：¥140万元
- 总工时：682人天
- 开发周期：19周
- 核心模块：15个
- 验收标准：144项

---

## 变更类型说明

- **新增（Added）**：新功能
- **变更（Changed）**：已有功能的变更
- **弃用（Deprecated）**：即将移除的功能
- **移除（Removed）**：已移除的功能
- **修复（Fixed）**：Bug修复
- **安全（Security）**：安全相关的修复

---

## 版本规则

- **主版本号**：不兼容的API变更
- **次版本号**：向下兼容的功能新增
- **修订号**：向下兼容的Bug修复

---

**最后更新**：2026年10月17日
