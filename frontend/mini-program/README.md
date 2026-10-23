# 微信小程序

劳务供应链交易平台的微信小程序预留目录。

## 用途、约定与禁止事项

- **现状**：当前只有 README，无依赖清单或页面源码；下文 uni-app 版本、功能与命令为历史规划参考。
- **用途**：承载经批准的小程序场景，不自动覆盖独立 App 的范围。
- **必须遵守**：初始化前确认平台账号、合法域名、隐私告知与授权范围；微信身份须通过可信服务端链路绑定，不信任客户端自报身份。
- **禁止**：不得提交 AppSecret、会话密钥或用户资料，不得强制收集无关个人信息，不得将工资、奖励展示等同于已批准或已完成付款。

共同约定：[前端总入口](../README.md)。

## 📋 技术栈

- **框架**：uni-app 3.x
- **UI组件库**：uni-ui
- **语言**：JavaScript / TypeScript
- **构建工具**：HBuilderX / Vite

## 🏗️ 项目结构

```
mini-program/
├── pages/                 # 页面
│   ├── index/            # 首页
│   ├── salary/           # 工资查询
│   ├── rebate/           # 返费查询
│   └── ...
├── components/           # 组件
├── static/               # 静态资源
│   ├── images/
│   └── icons/
├── utils/                # 工具函数
│   ├── request.js
│   └── auth.js
├── store/                # 状态管理（vuex/pinia）
├── manifest.json         # 应用配置
├── pages.json            # 页面路由配置
├── App.vue               # 应用入口
├── main.js               # 入口文件
└── README.md             # 本文件
```

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

```vue
<template>
  <view class="container">
    <view class="content">
      <!-- 内容 -->
    </view>
  </view>
</template>

<script>
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
      const res = await this.$http.get('/api/employees')
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

```javascript
// utils/request.js
const BASE_URL = 'https://api.example.com'

export const request = (options) => {
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Authorization': uni.getStorageSync('token')
      },
      success: (res) => {
        if (res.data.code === 200) {
          resolve(res.data)
        } else {
          reject(res.data)
        }
      },
      fail: reject
    })
  })
}
```

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
