# 文档管理规范

## 🎯 核心原则

**避免文档工程化，只保留必要文档，明确留档规则和位置。**

---

## 📋 留档决策树

### 1. 是否需要留档？

```
任务完成后，问自己3个问题：

Q1: 这个任务的产出物是否需要被未来的开发人员/AI助手查阅？
    ├─ 是 → 继续Q2
    └─ 否 → ❌ 不留档（Session历史记录已足够）

Q2: 这个任务的产出物是否会频繁变更？
    ├─ 是 → 继续Q3
    └─ 否 → ✅ 留档（作为历史快照）

Q3: 这个任务的产出物能否从代码/配置文件中直接获取？
    ├─ 是 → ❌ 不留档（代码即文档）
    └─ 否 → ✅ 留档（需要额外说明）
```

### 2. 留档的典型场景

| 场景 | 是否留档 | 原因 |
|------|---------|------|
| **实现了P0/P1能力** | ✅ 留档 | 需要告诉后续开发者有哪些能力可用 |
| **修复了一个Bug** | ❌ 不留档 | Git Commit记录已足够 |
| **重构了某个模块** | ❌ 不留档 | 代码本身说明了新结构 |
| **设计了技术架构** | ✅ 留档 | 架构决策需要长期保留 |
| **编写了临时脚本** | ❌ 不留档 | 脚本本身已在scripts/目录 |
| **创建了数据库表** | ✅ 留档（初版） | 表结构需要被查阅，但后续DDL变更不留档 |
| **完成了代码Review** | ❌ 不留档 | PR记录已足够 |
| **添加了配置项** | ❌ 不留档 | 配置文件本身已说明 |
| **编写了单元测试** | ❌ 不留档 | 测试代码本身即文档 |
| **制定了开发规范** | ✅ 留档 | 规范需要长期遵守 |

---

## 📁 文档目录结构（强制）

```
LaborAntermediaries/
├── CLAUDE.md                    # 技术规范总纲（唯一入口）
├── README.md                    # 项目介绍（对外展示）
├── CHANGELOG.md                 # 版本变更日志（自动生成）
│
├── docs/                        # 文档目录
│   ├── architecture/            # 架构文档（不常变）
│   │   ├── system-design.md        # 系统架构设计
│   │   ├── database-design.md      # 数据库设计（初版）
│   │   └── tech-stack.md           # 技术栈说明
│   │
│   ├── development/             # 开发文档（会变化）
│   │   ├── getting-started.md      # 快速开始
│   │   ├── coding-guide.md         # 编码指南
│   │   └── troubleshooting.md      # 常见问题（持续追加）
│   │
│   ├── api/                     # API文档（Swagger生成）
│   │   └── README.md               # API文档索引（指向Swagger UI）
│   │
│   ├── deployment/              # 部署文档（不常变）
│   │   ├── environment.md          # 环境配置
│   │   ├── ci-cd.md                # CI/CD流程
│   │   └── rollback.md             # 回滚方案
│   │
│   └── archived/                # 归档文档（历史快照）
│       ├── 2026-Q4/
│       │   ├── p0-implementation.md   # P0能力实现记录
│       │   └── p1-implementation.md   # P1能力实现记录
│       └── README.md               # 归档说明
│
├── backend/                     # 后端代码
│   ├── README.md                   # 后端项目说明（简短）
│   ├── CHANGELOG.md                # 后端变更日志
│   └── src/
│       └── main/
│           └── java/
│               └── com/company/labor/
│                   └── common/
│                       ├── util/README.md      # 工具类使用说明（合并文档）
│                       └── config/README.md    # 配置类说明（合并文档）
│
└── scripts/                     # 脚本目录
    └── README.md                   # 脚本使用说明
```

---

## 🗑️ 立即清理的文档

### 应该删除的文档（重复/过期/无用）

```bash
# ❌ 删除：重复的规范文档
rm CODE_STANDARDS.md              # 内容已合并到CLAUDE.md
rm CODE_CHEATSHEET.md             # 内容已合并到QUICK_REFERENCE.md
rm CONFIG_FILES.md                # 配置文件本身已说明
rm STANDARDS_README.md            # 重复
rm ENFORCE_STANDARDS.md           # 内容合并到CLAUDE.md

# ❌ 删除：临时实现文档（代码已完成）
rm backend/P0_IMPLEMENTATION_SUMMARY.md  # 临时文档
rm backend/PROJECT_SUMMARY.md             # 临时文档
rm backend/DELIVERY_CHECKLIST.md          # 临时文档

# ✅ 归档：长期保留但不常查阅
mv backend/P0_IMPLEMENTATION_COMPLETE.md docs/archived/2026-Q4/
mv backend/P1_IMPLEMENTATION_COMPLETE.md docs/archived/2026-Q4/

# ✅ 保留：核心文档
CLAUDE.md                 # 技术规范总纲
QUICK_REFERENCE.md        # 快速参考卡片
README.md                 # 项目介绍
```

### 合并后的核心文档结构

```
LaborAntermediaries/
├── CLAUDE.md                    # 技术规范总纲（10KB）
│   ├── 技术架构约束
│   ├── 数据库设计规范
│   ├── 核心能力规范（P0/P1）  ← 新增
│   ├── 代码规范
│   ├── 安全规范
│   ├── API接口规范
│   ├── 测试规范
│   ├── Git规范
│   └── 严格禁止事项
│
├── QUICK_REFERENCE.md           # 快速参考（2KB）
│   ├── 五层保护机制
│   ├── 开发前/中/后检查清单
│   ├── 绝对禁止事项
│   └── Skills使用方法
│
├── README.md                    # 项目介绍（3KB）
│   ├── 项目简介
│   ├── 技术栈
│   ├── 快速开始
│   └── 文档索引
│
└── docs/
    ├── architecture/
    │   ├── system-design.md        # 系统架构（5KB）
    │   └── database-design.md      # 数据库设计（3KB）
    │
    ├── development/
    │   ├── getting-started.md      # 快速开始（2KB）
    │   └── troubleshooting.md      # 常见问题（持续追加）
    │
    └── archived/
        └── 2026-Q4/
            ├── p0-implementation.md   # P0能力实现记录
            └── p1-implementation.md   # P1能力实现记录
```

**核心文档总量：5个核心文档 + 4个辅助文档 = 9个文档**

---

## 📝 留档规则（强制）

### 规则1：核心文档优先合并，不要新增

| 场景 | 错误做法 | 正确做法 |
|------|---------|---------|
| 新增一个P2能力 | ❌ 创建`P2_IMPLEMENTATION.md` | ✅ 在CLAUDE.md中追加"P2能力规范"章节 |
| 修改了金额计算规范 | ❌ 创建`MONEY_CALCULATION_UPDATE.md` | ✅ 直接修改CLAUDE.md对应章节 |
| 新增了CheckStyle规则 | ❌ 创建`CHECKSTYLE_RULES.md` | ✅ 在QUICK_REFERENCE.md中追加 |
| 实现了新的工具类 | ❌ 创建`NEW_UTIL_DOC.md` | ✅ 在backend/src/.../util/README.md中追加 |

### 规则2：代码即文档，优先写注释

| 场景 | 错误做法 | 正确做法 |
|------|---------|---------|
| 工具类使用说明 | ❌ 创建独立文档 | ✅ JavaDoc注释 + 类头注释 |
| 配置项说明 | ❌ 创建配置文档 | ✅ application.yml中写注释 |
| API接口说明 | ❌ 创建API文档 | ✅ Swagger注解 + 自动生成 |
| 数据库表说明 | ❌ 创建表结构文档 | ✅ SQL COMMENT + ERD图 |

### 规则3：历史快照归档，不要散落各处

| 场景 | 错误做法 | 正确做法 |
|------|---------|---------|
| P0能力实现完成 | ❌ 保留在backend/根目录 | ✅ 归档到docs/archived/2026-Q4/ |
| 老版本需求文档 | ❌ 保留在docs/根目录 | ✅ 归档到docs/archived/2026-Q4/ |
| 已废弃的技术方案 | ❌ 注释掉但保留 | ✅ 归档或删除 |

### 规则4：持续追加的文档，单文件维护

| 文档 | 维护方式 | 示例 |
|------|---------|------|
| `docs/development/troubleshooting.md` | 持续追加常见问题 | ✅ 单文件，按时间倒序 |
| `backend/src/.../util/README.md` | 持续追加工具类说明 | ✅ 单文件，按类名排序 |
| `CHANGELOG.md` | 自动生成版本日志 | ✅ Git自动生成 |

---

## 🎯 未来任务留档决策

### 场景1：实现了新的P2能力

**任务**：实现了6个P2能力（Excel导入导出、文件上传下载等）

**决策**：
1. ✅ 在CLAUDE.md中追加"P2能力规范"章节（5KB）
2. ✅ 在QUICK_REFERENCE.md中追加快速使用方法（0.5KB）
3. ✅ 在backend/src/.../util/README.md中追加工具类说明（2KB）
4. ❌ 不创建`P2_IMPLEMENTATION_COMPLETE.md`
5. ❌ 不创建单独的P2文档

**原因**：核心规范合并到CLAUDE.md，工具类说明在代码目录，避免文档分散。

---

### 场景2：修复了重大Bug

**任务**：修复了工资计算精度问题

**决策**：
1. ✅ Git Commit写清楚问题和修复方案
2. ✅ 如果是常见问题，追加到`docs/development/troubleshooting.md`
3. ❌ 不创建`BUG_FIX_RECORD.md`

**原因**：Git历史已记录，常见问题追加到troubleshooting，避免文档碎片化。

---

### 场景3：重构了某个模块

**任务**：重构了员工管理模块的Service层

**决策**：
1. ✅ Git Commit写清楚重构原因和新结构
2. ✅ 更新backend/README.md中的模块说明（如果有必要）
3. ❌ 不创建`REFACTOR_RECORD.md`

**原因**：代码本身说明了新结构，Git历史记录了变更。

---

### 场景4：设计了新的技术架构

**任务**：设计了分布式任务调度架构

**决策**：
1. ✅ 在`docs/architecture/task-scheduler.md`中新增文档（5KB）
2. ✅ 在CLAUDE.md中追加"任务调度规范"章节（如果有必要）
3. ❌ 不创建`TASK_SCHEDULER_DESIGN.md`（在根目录）

**原因**：架构文档统一在docs/architecture/目录，避免根目录文档泛滥。

---

### 场景5：编写了迁移脚本

**任务**：编写了数据迁移脚本migrate_tenant_data.sql

**决策**：
1. ✅ 脚本保存在`scripts/migration/migrate_tenant_data.sql`
2. ✅ 在`scripts/README.md`中追加使用说明（0.5KB）
3. ❌ 不创建`MIGRATION_GUIDE.md`

**原因**：脚本本身已有注释，README统一索引所有脚本。

---

## 🔄 文档维护流程

### 每次任务完成后

```
1. 问自己：这个任务需要留档吗？
   ├─ 需要 → 继续步骤2
   └─ 不需要 → ✅ 结束（Session历史已足够）

2. 这个文档应该放在哪里？
   ├─ 技术规范 → 合并到CLAUDE.md
   ├─ 快速参考 → 合并到QUICK_REFERENCE.md
   ├─ 工具类说明 → 合并到backend/src/.../util/README.md
   ├─ 常见问题 → 追加到docs/development/troubleshooting.md
   ├─ 架构设计 → 新增到docs/architecture/xxx.md
   └─ 历史快照 → 归档到docs/archived/YYYY-QQ/

3. 是否需要删除老文档？
   ├─ 是 → 删除或归档
   └─ 否 → ✅ 结束

4. 是否需要更新README索引？
   ├─ 是 → 更新README.md
   └─ 否 → ✅ 结束
```

### 每季度文档清理

```bash
# 1. 归档本季度的重要文档
mkdir -p docs/archived/2026-Q4
mv backend/P0_IMPLEMENTATION_COMPLETE.md docs/archived/2026-Q4/
mv backend/P1_IMPLEMENTATION_COMPLETE.md docs/archived/2026-Q4/

# 2. 删除重复/过期文档
rm CODE_STANDARDS.md
rm CODE_CHEATSHEET.md
rm CONFIG_FILES.md

# 3. 合并碎片化文档
# 将多个小文档合并到一个核心文档中

# 4. 更新README索引
# 确保README指向正确的文档
```

---

## 📊 文档数量控制目标

| 类型 | 目标数量 | 当前数量 | 说明 |
|------|---------|---------|------|
| **根目录核心文档** | ≤5个 | 3个 | CLAUDE.md, QUICK_REFERENCE.md, README.md |
| **architecture/** | ≤5个 | 0个 | 系统架构、数据库设计等 |
| **development/** | ≤3个 | 0个 | 快速开始、常见问题 |
| **archived/** | 不限 | 0个 | 历史快照，按季度归档 |
| **backend/README.md** | 1个 | 1个 | 后端项目说明 |
| **总计** | **≤15个** | **4个** | 严格控制文档数量 |

**当前文档数量**：58个（❌ 严重超标）  
**目标文档数量**：15个（✅ 控制在15个以内）

---

## ✅ 执行清理计划

### 第1步：删除重复/临时文档（立即）

```bash
cd D:\codeSpace\LaborAntermediaries

# 删除重复的规范文档
rm CODE_STANDARDS.md
rm CODE_CHEATSHEET.md
rm CONFIG_FILES.md
rm STANDARDS_README.md
rm ENFORCE_STANDARDS.md

# 删除临时实现文档
rm backend/P0_IMPLEMENTATION_SUMMARY.md
rm backend/PROJECT_SUMMARY.md
rm backend/DELIVERY_CHECKLIST.md
```

### 第2步：归档历史文档（立即）

```bash
# 创建归档目录
mkdir -p docs/archived/2026-Q4

# 归档P0/P1实现文档
mv backend/P0_IMPLEMENTATION_COMPLETE.md docs/archived/2026-Q4/
mv backend/P1_IMPLEMENTATION_COMPLETE.md docs/archived/2026-Q4/

# 归档老的需求文档
mv docs/归档文件/* docs/archived/2026-Q4/
rmdir docs/归档文件
```

### 第3步：整理售前文档（保留但不常查阅）

```bash
# 售前文档保留，但不常查阅
# docs/01-售前阶段/ → 保留
# docs/02-开发阶段/ → 保留
# docs/03-交付阶段/ → 保留
# docs/04-运维阶段/ → 保留

# 这些文档属于项目管理文档，不是技术文档
# 不参与技术文档数量统计
```

### 第4步：合并工具类文档（下次实现P2时）

```bash
# 未来实现P2时，不创建独立文档
# 而是在backend/src/.../util/README.md中追加
```

---

## 🎯 总结

### 核心原则

1. **代码即文档** - 优先写注释，不写独立文档
2. **核心文档优先合并** - 追加到CLAUDE.md，不新增文档
3. **历史快照归档** - 季度归档，不散落各处
4. **持续追加单文件** - 常见问题、工具类说明用单文件维护
5. **严格控制数量** - 技术文档≤15个

### 未来留档决策

| 任务类型 | 是否留档 | 留档位置 |
|---------|---------|---------|
| 实现P2能力 | ✅ | 合并到CLAUDE.md |
| 修复Bug | ❌ | Git Commit |
| 重构模块 | ❌ | Git Commit |
| 设计架构 | ✅ | docs/architecture/ |
| 编写脚本 | ✅ | scripts/ + README |
| 常见问题 | ✅ | 追加到troubleshooting.md |
| 工具类说明 | ✅ | 追加到backend/.../README.md |

### 避免文档工程化

- ✅ **合并**：将相关内容合并到核心文档
- ✅ **归档**：历史文档按季度归档
- ✅ **删除**：重复/过期文档立即删除
- ❌ **禁止**：为每个任务创建独立文档

---

**制定人**：AI开发团队  
**最后更新**：2026年10月18日  
**版本**：v1.0
