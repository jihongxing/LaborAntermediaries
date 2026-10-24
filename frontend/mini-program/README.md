# 微信小程序

劳务供应链交易平台的微信小程序预留目录。

## 用途、约定与禁止事项

- **现状**：当前只有 README，无依赖清单或页面源码；技术栈版本、功能与命令为历史规划参考，新增文件遵循下方目录规划。
- **用途**：承载经批准的小程序场景，不自动覆盖独立 App 的范围。
- **必须遵守**：初始化前确认平台账号、合法域名、隐私告知与授权范围；微信身份须通过可信服务端链路绑定，不信任客户端自报身份。
- **禁止**：不得提交 AppSecret、会话密钥或用户资料，不得强制收集无关个人信息，不得将工资、奖励展示等同于已批准或已完成付款。

共同约定：[前端总入口](../README.md)。

## 📋 技术栈

- **框架**：uni-app 3.x
- **UI组件库**：uni-ui
- **语言**：JavaScript / TypeScript
- **构建工具**：HBuilderX / Vite

## 目录规划与新增文件规则

以下按规划中的 uni-app 展示源码根内的职责，不代表工程或功能已实现。初始化时确认使用 CLI 的 `src/` 源码根还是 HBuilderX 项目根，并更新本节为唯一实际路径；不要同时维护两套入口。这里只约定文件归属，不新增依赖或预建全部目录。

```text
<源码根>/
├── pages/                       主包页面入口与页面局部 UI
│   └── <module>/<page>/         按业务归类，不集中全部业务逻辑
├── modules/
│   └── <module>/
│       ├── api/                 业务请求与传输模型
│       ├── model/               业务状态与纯规则
│       ├── components/          主包使用的业务专属 UI，按需新增
│       └── index.ts             必要的公开入口，按需新增
├── packages/                    分包，确认装载需求后按需新增
│   └── <module>/                与 pages.json 中的分包 root 对应
│       ├── pages/               分包页面入口
│       ├── components/          分包专属 UI
│       ├── api/                 分包专属请求
│       ├── model/               分包专属状态与纯规则
│       └── static/              分包专属资源
├── platform/
│   ├── http/                    请求与错误转换
│   ├── session/                 会话、微信登录链路的客户端封装
│   ├── storage/                 本地存储封装
│   └── wechat/                  微信能力适配，按需新增
├── shared/
│   ├── ui/                      无业务归属的复用 UI
│   ├── lib/                     纯工具与通用组合逻辑
│   └── styles/                  主题与公共样式
├── static/                      主包公开资源，按业务或 shared 分类
├── pages.json                   页面、导航及分包注册
├── manifest.json                应用与平台声明，不放 AppSecret
├── App.vue
└── main.ts                      入口语言在初始化时确认
```

工程级依赖清单、锁文件、构建配置、`README.md` 放在 `mini-program/` 根；测试统一按需放在工程根的 `tests/`，不随源码根选择而维护两套测试。

### 放置与依赖规则

- `pages` 是框架页面入口，不是全局业务实现仓库；页面局部交互可以就近维护，可复用的业务请求、状态与规则放所属 `modules/<module>`。请求封装放 `platform/http`，不新建包含所有业务的 `utils/request`、全局 `api` 或全局业务 Store。
- 先决定主包或分包归属，再放文件。一个功能整体进入分包时，其专属 UI、请求、状态和资源放在该 `packages/<module>` 内，不在 `modules` 和 `packages` 复制一份；主包入口不反向导入分包内部实现。
- 页面和分包路径同步登记到 `pages.json`；初始化时结合所选工具链验证装载方式、资源归属和构建体积，不把创建目录等同于完成分包，也不预先写死平台体积限制。
- 分包不直接引用其他分包内部代码；普通分包需要共用的无业务能力按实际构建约束放到主包公共目录。若采用独立分包，先单独评审其依赖与资源自包含方案，不假定它可以复用主包运行时。
- 依赖方向为页面入口到所属业务，再到 `platform` / `shared`；`platform`、`shared` 不直接导入业务模块，`shared` 不依赖平台实现。跨业务仅通过公开入口协作，框架的全局入口只做初始化，不承载业务规则。
- `tests/` 镜像源码根下的逻辑路径，例如 `tests/modules/<module>/`、`tests/packages/<module>/`；跨页面场景放 `tests/e2e/`，合成样例放 `tests/fixtures/`。具体测试工具和命令在初始化时确定。
- `static` 内资源视为可被客户端获取，不放真实个人资料或秘密；运行时缓存与上传文件不写入源码。`dist/`、`unpackage/`、`node_modules/` 等生成目录不存手工资料；拆分和公共提取遵循[前端共同规则](../README.md#目录规划与新增文件规则)。

### 放置示例

以下仅说明未来获批需求的位置，`<源码根>` 在初始化时确定：

| 新增内容 | 位置 |
|---|---|
| 主包中的招聘列表入口 | `<源码根>/pages/recruitment/list/`，同步注册页面 |
| 主包招聘查询请求与状态 | `<源码根>/modules/recruitment/api/`、`model/` |
| 独立装载的招聘普通分包 | `<源码根>/packages/recruitment/`，专属实现随分包移动，不保留重复实现 |
| 微信授权能力适配 | `<源码根>/platform/wechat/`，身份验证与绑定仍由可信后端完成 |
| 纯字符串格式化 | 优先复用 `<源码根>/shared/lib/` 内已有能力 |
| 主包招聘规则单元测试 | `tests/modules/recruitment/model/` |

## 🚀 快速开始

### 环境要求

- Node.js 18+
- HBuilderX 或 微信开发者工具

### 安装依赖

```bash
npm install
```

### 运行开发

```bash
# 微信小程序
npm run dev:mp-weixin

# H5
npm run dev:h5
```

### 构建生产版本

```bash
# 微信小程序
npm run build:mp-weixin

# H5
npm run build:h5
```

## 🔧 配置

### manifest.json

```json
{
  "name": "劳务中介管理系统",
  "appid": "your_appid",
  "description": "",
  "versionName": "1.0.0",
  "versionCode": "100",
  "mp-weixin": {
    "appid": "your_weixin_appid"
  }
}
```

### pages.json

```json
{
  "pages": [
    {
      "path": "pages/index/index",
      "style": {
        "navigationBarTitleText": "首页"
      }
    }
  ],
  "globalStyle": {
    "navigationBarTextStyle": "black",
    "navigationBarTitleText": "劳务中介管理",
    "navigationBarBackgroundColor": "#F8F8F8",
    "backgroundColor": "#F8F8F8"
  }
}
```

## 📝 开发规范

### 页面结构

以下仅演示主包页面组织；业务调用委托所属模块，别名与请求实现需在初始化时配置，不是已有可运行页面。

```vue
<template>
  <view class="container">
    <view class="content">
      <!-- 内容 -->
    </view>
  </view>
</template>

<script>
import { getEmployeeList } from '@/modules/employee/api/employee'

export default {
  data() {
    return {
      list: []
    }
  },
  onLoad() {
    this.fetchData()
  },
  methods: {
    async fetchData() {
      const res = await getEmployeeList()
      this.list = res.data
    }
  }
}
</script>

<style scoped>
.container {
  padding: 20rpx;
}
</style>
```

### API调用

主包员工查询的 `getEmployeeList` 放在 `<源码根>/modules/employee/api/employee.ts`；它使用 `<源码根>/platform/http/` 的请求能力，不在页面内调用 `uni.request`。

- `platform/http` 统一处理基础 URL、认证头、HTTP 与业务错误，按后端契约使用 Bearer 认证，不将本地读取的 Token 原样作为认证头。
- `platform/session` 负责会话生命周期与退出清理，存储策略经安全评审确定；目录规划不表示已具备安全存储或可信身份验证能力。
- 若员工查询最终归属分包，其业务请求随功能放入对应 `packages/<module>/api/`，不再保留一份主包实现。

## 📱 微信小程序审核

### 审核前检查

- [ ] 填写完整的小程序信息
- [ ] 设置正确的类目（人力资源服务）
- [ ] 准备测试账号
- [ ] 准备隐私政策链接
- [ ] 确保无敏感词汇（如"贷款"等）

### 常见审核问题

1. **类目不匹配**：确保选择"人力资源服务"类目
2. **功能描述不清**：详细描述小程序功能
3. **缺少用户协议**：添加用户协议和隐私政策

## 📚 相关文档

- [uni-app官方文档](https://uniapp.dcloud.net.cn/)
- [微信小程序官方文档](https://developers.weixin.qq.com/miniprogram/dev/framework/)
- [技术规范](../../CLAUDE.md)

## 🤝 贡献

请参考 [贡献指南](../../CONTRIBUTING.md)
