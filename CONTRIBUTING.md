# 贡献指南

> 后端运行与测试说明已更新：以 `backend/README.md` 为准。当前使用 Java 17 + PostgreSQL 16 + Flyway，不依赖 MySQL、Redis 或手工业务初始化脚本；完整验证使用专用 PostgreSQL 测试库执行 `mvn -Pintegration clean verify`。旧 SaaS 业务示例仅保留历史参考。

欢迎参与劳务中介管理系统的开发！本文档将帮助你了解如何为项目做出贡献。

---

## 📋 目录

1. [行为准则](#行为准则)
2. [开发环境搭建](#开发环境搭建)
3. [开发流程](#开发流程)
4. [代码规范](#代码规范)
5. [提交规范](#提交规范)
6. [Pull Request流程](#pull-request流程)
7. [问题反馈](#问题反馈)

---

## 🤝 行为准则

### 我们的承诺

为了营造开放和友好的环境，我们作为贡献者和维护者承诺：
- ✅ 尊重所有人，无论其经验水平、性别、性别认同、性取向、残疾、外貌、体型、种族、民族、年龄、宗教或国籍
- ✅ 使用友好和包容的语言
- ✅ 尊重不同的观点和经验
- ✅ 优雅地接受建设性批评

### 不可接受的行为

- ❌ 使用性暗示的语言或图像
- ❌ 人身攻击或侮辱性评论
- ❌ 骚扰他人
- ❌ 发布他人的私人信息
- ❌ 其他不专业的行为

---

## 💻 开发环境搭建

### 前置要求

**后端开发**：
- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 7.0+
- IntelliJ IDEA（推荐）

**前端开发**：
- Node.js 18+
- npm 或 pnpm
- VS Code（推荐）

### 克隆项目

```bash
# 克隆仓库
git clone https://github.com/your-org/LaborIntermediaries.git
cd LaborAntermediaries

# 查看项目结构
tree -L 2
```

### 后端环境搭建

```bash
# 进入后端目录
cd backend

# 安装依赖
mvn clean install

# 配置数据库
# 1. 创建数据库：CREATE DATABASE labor_intermediaries;
# 2. 执行初始化脚本：database/schema/V1.0.0__init.sql

# 启动后端
mvn spring-boot:run
```

### 前端环境搭建

```bash
# Web管理后台
cd frontend/web-admin
npm install
npm run dev

# 移动App（Flutter）
cd frontend/mobile-app
flutter pub get
flutter run

# 微信小程序（uni-app）
cd frontend/mini-program
npm install
npm run dev:mp-weixin
```

---

## 🔄 开发流程

### 1. 创建Issue

在开始开发前，请先创建或认领一个Issue：
- Bug修复：使用`bug`标签
- 新功能：使用`feature`标签
- 文档改进：使用`documentation`标签

### 2. 创建分支

从`develop`分支创建功能分支：

```bash
# 更新develop分支
git checkout develop
git pull origin develop

# 创建功能分支
git checkout -b feature/your-feature-name

# 或创建修复分支
git checkout -b hotfix/fix-description
```

**分支命名规范**：
- `feature/xxx`：新功能
- `fix/xxx`：Bug修复
- `docs/xxx`：文档更新
- `refactor/xxx`：代码重构
- `test/xxx`：测试相关

### 3. 开发与提交

```bash
# 开发代码
# ...

# 运行测试
cd backend
mvn test  # 后端
cd ..
npm test  # 前端

# 提交代码（遵循提交规范）
git add .
git commit -m "feat: 新增员工管理模块"
```

### 4. 推送分支

```bash
git push origin feature/your-feature-name
```

### 5. 创建Pull Request

在GitHub上创建Pull Request，并：
- 填写PR描述模板
- 关联相关Issue
- 请求Code Review

---

## 📝 代码规范

### 后端代码规范

**遵循《阿里巴巴Java开发手册》**

**强制要求**：
1. ✅ Controller层只做参数校验和转发
2. ✅ Service层写业务逻辑
3. ✅ Mapper层只做数据库操作
4. ✅ 金额必须用`BigDecimal`，不用`double`
5. ✅ 所有业务表必须有`tenant_id`字段
6. ✅ 新增或修改代码必须补齐中文注释；Java 类型、手写方法与构造器、字段及关键逻辑的具体要求以
   [注释规范](CLAUDE.md#注释规范强制) 和 [后端执行规则](backend/AGENTS.md) 为准，基础设施代码不例外。

**示例**：
```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    @Autowired
    private EmployeeService employeeService;
    
    @GetMapping("/{id}")
    public Result<EmployeeVO> getById(@PathVariable Long id) {
        return Result.success(employeeService.getById(id));
    }
}
```

### 前端代码规范

**Vue 3规范**：
- 组件命名：大驼峰（PascalCase）
- 变量命名：小驼峰（camelCase）
- 使用`<script setup>`语法糖
- 使用Composition API

**示例**：
```vue
<template>
  <div class="employee-list">
    <el-table :data="tableData">
      <!-- ... -->
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getEmployeeList } from '@/api/employee'

const tableData = ref([])

const fetchData = async () => {
  const res = await getEmployeeList()
  tableData.value = res.data
}

onMounted(() => {
  fetchData()
})
</script>
```

### 测试规范

**必须编写测试**：
- 核心业务逻辑必须有单元测试
- 测试覆盖率≥80%
- 使用JUnit 5 + Mockito

**示例**：
```java
@Test
@DisplayName("工资计算：正常工时 + 加班工时")
public void testCalculateSalary() {
    // Given
    Long employeeId = 1L;
    String month = "2024-10";
    
    // When
    SalaryDetail result = salaryService.calculateSalary(employeeId, month);
    
    // Then
    assertEquals(new BigDecimal("5500.00"), result.getGrossSalary());
}
```

---

## 📤 提交规范

### Commit消息格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type类型**：
- `feat`：新功能
- `fix`：Bug修复
- `docs`：文档更新
- `style`：代码格式（不影响功能）
- `refactor`：重构（不是新功能也不是Bug修复）
- `test`：测试相关
- `chore`：构建过程或辅助工具的变动

**示例**：

```
feat(employee): 新增员工管理模块

实现功能：
- 员工列表查询
- 员工详情查看
- 员工新增/编辑
- 员工删除

Closes #123
```

```
fix(salary): 修复工资计算精度问题

问题描述：
工资计算时使用double导致精度丢失

解决方案：
改用BigDecimal进行金额计算

影响范围：
SalaryService.calculateSalary()方法

Fixes #456
```

### Commit消息规则

1. ✅ 使用祈使语气（"新增"而不是"新增了"）
2. ✅ 第一行不超过50个字符
3. ✅ 正文每行不超过72个字符
4. ✅ 正文与标题空一行
5. ✅ 可以使用中文或英文

---

## 🔍 Pull Request流程

### 创建PR

1. **填写PR模板**：
   - PR标题遵循commit规范
   - 描述变更内容和原因
   - 关联相关Issue

2. **PR检查清单**：
   - [ ] 代码遵循项目规范
   - [ ] 已编写单元测试
   - [ ] 所有测试通过
   - [ ] 已更新相关文档
   - [ ] 无编译警告

3. **请求审查**：
   - 普通PR：至少1人审查
   - 核心模块：至少2人审查

### Code Review

**审查人检查项**：
- [ ] 代码逻辑是否正确
- [ ] 是否有明显的bug
- [ ] 是否有性能问题
- [ ] 是否有安全问题
- [ ] 代码是否符合规范
- [ ] 注释是否清晰
- [ ] 测试是否充分

**审查意见**：
- ✅ **Approve**：代码无问题，可以合并
- 💬 **Comment**：有建议但不阻塞合并
- ❌ **Request Changes**：有问题必须修改

### 合并PR

满足以下条件才能合并：
1. ✅ 至少1个Approve（核心模块需2个）
2. ✅ CI自动测试通过
3. ✅ 无未解决的Review意见
4. ✅ 无冲突

**合并方式**：
- 使用`Squash and merge`（推荐）
- 合并后删除分支

---

## 🐛 问题反馈

### 报告Bug

使用Bug报告模板（`.github/ISSUE_TEMPLATE/bug_report.md`）：

**包含信息**：
1. Bug描述
2. 复现步骤
3. 预期行为
4. 实际行为
5. 环境信息
6. 截图/录屏

**优先级**：
- `P0`：紧急（系统崩溃、数据丢失）
- `P1`：重要（核心功能不可用）
- `P2`：一般（普通功能异常）
- `P3`：轻微（UI问题、优化建议）

### 功能建议

使用功能请求模板（`.github/ISSUE_TEMPLATE/feature_request.md`）：

**包含信息**：
1. 功能描述
2. 使用场景
3. 预期收益
4. 实现建议（可选）

---

## 📚 参考资料

### 项目文档
- [README.md](./README.md) - 项目总览
- [CLAUDE.md](./CLAUDE.md) - 技术规范
- [GOVERNANCE.md](./GOVERNANCE.md) - 工程治理
- [IDEA.md](./IDEA.md) - 项目创意

### 外部资源
- [阿里巴巴Java开发手册](https://github.com/alibaba/p3c)
- [Vue 3官方文档](https://cn.vuejs.org/)
- [Spring Boot官方文档](https://spring.io/projects/spring-boot)
- [Keep a Changelog](https://keepachangelog.com/zh-CN/)

---

## 💬 联系我们

**项目负责人**：[姓名]  
**技术负责人**：[姓名]  
**邮箱**：[邮箱]  
**微信群**：[二维码]

---

## 🎉 感谢贡献

感谢所有为本项目做出贡献的开发者！

<!-- 贡献者列表将自动生成 -->

---

**最后更新**：2026年10月17日
