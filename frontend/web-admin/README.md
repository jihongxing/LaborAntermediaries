# Web管理后台

劳务供应链交易平台的 Web 管理端预留目录。

## 用途、约定与禁止事项

- **现状**：只有 README、ESLint 与 Prettier 配置，无 `package.json` 和应用源码；技术栈版本与运行命令为历史规划参考，新增文件遵循下方目录规划。
- **用途**：承载经批准的 Web 操作与管理界面，具体角色和范围以当前 PRD 为准。
- **必须遵守**：工程初始化先评审依赖与工具链，补齐锁文件、环境示例、测试及 CI；复用后端接口契约，对敏感字段和导出进行授权设计。
- **禁止**：不得依赖页面隐藏实现权限隔离，不得把数据库凭据或签名私钥放入浏览器，不得将尚未实现的页面或支付能力标为可用。

共同约定：[前端总入口](../README.md)。

## 📋 技术栈

- **框架**：Vue 3.3+
- **构建工具**：Vite 4.x
- **语言**：TypeScript 5.x
- **UI组件库**：Element Plus 2.x
- **状态管理**：Pinia 2.x
- **路由**：Vue Router 4.x
- **HTTP客户端**：Axios

## 目录规划与新增文件规则

以下为目标放置模板，不代表目录或功能已实现；只在获批需求需要时创建。`<module>` 使用明确的小写英文业务名称，不按页面或数据库表机械划分模块。

```text
web-admin/
├── public/                    原样发布的公开资源，不存敏感文件
├── src/
│   ├── app/                   应用装配，不承载业务规则
│   │   ├── router/            路由汇总、导航守卫
│   │   ├── layouts/           应用布局
│   │   └── plugins/           全局插件注册，按需新增
│   ├── modules/
│   │   └── <module>/
│   │       ├── pages/         业务页面
│   │       ├── components/    业务专属 UI
│   │       ├── api/           业务请求及传输模型
│   │       ├── model/         业务状态、展示模型与纯规则
│   │       ├── composables/   业务组合逻辑，按需新增
│   │       ├── assets/        业务专属资源，按需新增
│   │       ├── routes.ts      本模块路由声明，按需新增
│   │       └── index.ts       必要的模块公开入口，按需新增
│   ├── platform/
│   │   ├── http/              请求、错误转换等基础能力
│   │   ├── session/           会话与令牌生命周期，不放业务角色规则
│   │   └── storage/           浏览器存储封装，按需新增
│   ├── shared/
│   │   ├── ui/                无业务归属的复用 UI
│   │   ├── lib/               纯工具与通用组合函数
│   │   └── styles/            主题与全局样式
│   ├── assets/                全应用共用、参与构建的图片与字体
│   ├── App.vue
│   └── main.ts
├── tests/
│   ├── e2e/                   跨页面端到端场景，按需新增
│   └── fixtures/              跨测试共用的合成数据，按需新增
├── .env.example               可公开的配置项示例，不放真实令牌
├── package.json / 锁文件       初始化时确定包管理器并提交对应锁文件
├── tsconfig.json / vite.config.ts
└── README.md
```

### 放置与依赖规则

- 新增业务页面、业务状态和请求放入同一个 `modules/<module>`；页面局部样式和只使用一次的 UI 留在页面附近，不为每个小文件预建目录。
- `api` 处理业务端点与传输转换，复用 `platform/http`；页面不重复实现鉴权头、错误转换和重试。业务校验留在业务模块，通用字符串校验才考虑 `shared/lib`。
- `app` 汇总模块路由并装配依赖；模块不反向依赖 `app`。`shared` 不依赖业务模块或 `platform`；需要平台行为的公共 UI 通过参数、事件或注入接口获取。
- `platform` 不直接引用业务模块；业务模块之间只通过明确的公开入口协作，不深层导入其他模块的页面、内部状态或请求实现。禁止把所有业务状态收进一个全局 Store。
- 单元测试和 UI 测试使用相邻的 `*.spec.ts`；跨页面流程放 `tests/e2e/`。测试框架及运行命令在工程初始化时确认，不把此目录规划当作已有测试能力。
- 出现独立子职责时再拆业务子目录；公共提取、跨端复用和 README 更新遵循[前端共同规则](../README.md#目录规划与新增文件规则)。构建目录 `dist/` 与依赖目录 `node_modules/` 不存放手工维护资料。

### 放置示例

以下仅表示未来获批需求的位置，不创建功能：

| 新增内容 | 位置 |
|---|---|
| 招聘列表页面及专属筛选 UI | `src/modules/recruitment/pages/`、`src/modules/recruitment/components/` |
| 招聘请求与列表状态 | `src/modules/recruitment/api/`、`src/modules/recruitment/model/` |
| 不含业务规则的通用分页 UI | 确认真实复用后放 `src/shared/ui/` |
| 统一 HTTP 错误转换 | `src/platform/http/`，不放 `shared/lib` |
| 招聘请求单元测试 | 与被测文件相邻的 `*.spec.ts` |
| 导航到详情页的端到端流程 | `tests/e2e/recruitment/` |

## 🚀 快速开始

### 安装依赖

```bash
npm install
# 或
pnpm install
```

### 开发模式

```bash
npm run dev
```

访问：http://localhost:3000

### 构建生产版本

```bash
npm run build
```

### 预览生产构建

```bash
npm run preview
```

### 代码检查

```bash
# Lint
npm run lint

# 类型检查
npm run type-check
```

## 🔧 环境配置

### 开发环境 (.env.development)

```env
# API基础URL
VITE_API_BASE_URL=http://localhost:8080/api

# 应用标题
VITE_APP_TITLE=劳务中介管理系统
```

### 生产环境 (.env.production)

```env
# API基础URL
VITE_API_BASE_URL=https://api.example.com/api

# 应用标题
VITE_APP_TITLE=劳务中介管理系统
```

## 📝 开发规范

### 组件命名

```vue
<!-- ✅ 正确：大驼峰 -->
<script setup lang="ts">
// EmployeeList.vue
</script>

<!-- ❌ 错误：小写或下划线 -->
<!-- employee-list.vue -->
<!-- employee_list.vue -->
```

### API调用

位置示例：`src/modules/employee/api/employee.ts`。以下仅演示模块归属，`@` 别名、请求封装及返回类型须在初始化时落地并对齐后端契约，不是当前可执行代码。

```typescript
import request from '@/platform/http/request'

export interface Employee {
  id: number
  name: string
  // ...
}

export const getEmployeeList = (params: any) => {
  return request<Employee[]>({
    url: '/employees',
    method: 'get',
    params
  })
}

export const getEmployeeById = (id: number) => {
  return request<Employee>({
    url: `/employees/${id}`,
    method: 'get'
  })
}
```

### 页面组件

```vue
<template>
  <div class="employee-list">
    <el-table :data="tableData" v-loading="loading">
      <el-table-column prop="name" label="姓名" />
      <!-- ... -->
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getEmployeeList } from '@/modules/employee/api/employee'
import type { Employee } from '@/modules/employee/api/employee'

const tableData = ref<Employee[]>([])
const loading = ref(false)

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getEmployeeList({ page: 1, size: 20 })
    tableData.value = res.data
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.employee-list {
  padding: 20px;
}
</style>
```

## 🧪 测试

```bash
# 运行单元测试
npm run test:unit

# 生成测试覆盖率报告
npm run test:coverage
```

## 📚 相关文档

- [Vue 3官方文档](https://cn.vuejs.org/)
- [Element Plus文档](https://element-plus.org/)
- [Vite文档](https://cn.vitejs.dev/)
- [技术规范](../../CLAUDE.md)

## 🤝 贡献

请参考 [贡献指南](../../CONTRIBUTING.md)
