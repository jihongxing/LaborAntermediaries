# Web管理后台

劳务中介管理系统 - Web管理后台（Vue 3 + Element Plus）

## 📋 技术栈

- **框架**：Vue 3.3+
- **构建工具**：Vite 4.x
- **语言**：TypeScript 5.x
- **UI组件库**：Element Plus 2.x
- **状态管理**：Pinia 2.x
- **路由**：Vue Router 4.x
- **HTTP客户端**：Axios

## 🏗️ 项目结构

```
web-admin/
├── public/                 # 静态资源
├── src/
│   ├── api/               # API接口
│   │   ├── employee.ts
│   │   ├── salary.ts
│   │   └── ...
│   ├── assets/            # 资源文件
│   │   ├── images/
│   │   ├── styles/
│   │   └── icons/
│   ├── components/        # 公共组件
│   │   ├── Table/
│   │   ├── Form/
│   │   └── ...
│   ├── views/             # 页面
│   │   ├── employee/      # 员工管理
│   │   ├── salary/        # 工资管理
│   │   └── ...
│   ├── router/            # 路由
│   │   └── index.ts
│   ├── store/             # 状态管理
│   │   ├── modules/
│   │   └── index.ts
│   ├── utils/             # 工具函数
│   │   ├── request.ts     # 请求封装
│   │   ├── auth.ts        # 认证工具
│   │   └── validate.ts    # 校验工具
│   ├── App.vue
│   └── main.ts
├── .env.development       # 开发环境配置
├── .env.production        # 生产环境配置
├── package.json
├── tsconfig.json
├── vite.config.ts
└── README.md              # 本文件
```

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

```typescript
// src/api/employee.ts
import request from '@/utils/request'

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
import { getEmployeeList } from '@/api/employee'
import type { Employee } from '@/api/employee'

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
