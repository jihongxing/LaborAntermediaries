# 劳务供需平台 Web

面向访客、组织成员和平台岗位的 Web 入口，使用 Vue 3、TypeScript、Vue Router、Vite、Element Plus 和 Axios。
根路径 `/` 是公开门户；本地开发预览另提供匿名需求详情、未认证账号状态和用工方/供应商组织空间。

## 运行边界

- 后端是资源服务器，不在本项目签发 JWT；真实账号登录和组织状态需要外部身份服务及服务端认证接口接入。
- JWT 和当前组织编号只保存在页面内存，刷新页面后清空；前端界面隐藏不能代替后端权限校验。
- “账号注册”属于外部身份服务；本工作台从“组织申请/加入”开始接入首批业务。
- 供应商开放需求只来自后端按租户生成的投递快照，页面不直接请求其他组织的需求源数据。
- 公共门户中的需求是本地开发预览用的合成样例，生产构建不包含该样例；当前后端没有公共需求授权、匿名投影或公开查询 API，未接入前不得对外展示业务需求。
- `/account`、`/workspace/employer` 和 `/workspace/supplier` 仅在 Vite 开发模式注册，用于评审页面结构；它们当前使用演示状态/数据，不代表真实登录、组织资格或业务处理已接通。
- `/__dev/console` 是仅开发模式可用的 API 联调入口，不会出现在公开导航，也不会编入生产构建。

## 安装与启动

在本目录执行：

```powershell
npm install
npm run dev
```

Web 管理端固定监听 `127.0.0.1:3000`，开发服务器严格使用此端口，不会自动递增到 3001、3002 等端口。若端口被占用，先检查监听 PID 的完整命令行：确认属于本目录的旧 Vite 服务后再停止并重启；若属于其他项目或无法确认归属，不要终止进程或改用新端口，应报告冲突。项目开发时只保留一个 Web 管理端服务。

从仓库根目录启动并自动处理本项目旧服务：

```powershell
.\tools\start-web-admin.ps1
```

脚本只会停止命令行明确指向本项目 `frontend/web-admin` 的旧 Vite 服务；端口被其他进程占用时会直接失败。

开发服务器将 `/api` 代理到 `http://localhost:8080`；可通过 `.env.local` 的 `VITE_DEV_API_TARGET` 覆盖。

API 基地址可通过 `VITE_API_BASE_URL` 配置，默认是同源 `/api`。公开配置示例见 [.env.example](.env.example)，不要把真实令牌写入 `.env` 或源码。

## 验证

```powershell
npm run type-check
npm run build
npm run lint
```

`@playwright/test` 已作为开发依赖安装。浏览器检查使用仓库 `/playwright` 技能提供的 CLI 流程；需要编写端到端用例时再新增 `tests/e2e/` 和对应 `playwright.config.ts`，不要把令牌或真实业务数据写入测试夹具。

## 页面与接口对应

门户披露字段和页面分层见[劳务供需门户与 Web 角色入口原型](../../docs/02-开发阶段/产品设计/00-产品全景/劳务供需门户与Web角色入口原型-v0.1.md)及[需求公开可见性标准](../../docs/02-开发阶段/产品设计/01-身份与需求交易/用工方入驻与有效需求标准-v0.1.md#741-公共门户与需求分阶段披露新增建议待业务规则评审)。

| 页面区域 | 主要接口 |
| --- | --- |
| 组织入驻 | `POST/GET/PUT /api/organizations`、`POST /submit` |
| 组织加入 | `POST /api/organizations/{id}/join`、`GET/POST /membership-requests` |
| 平台组织审核 | `GET /api/organizations/platform/pending`、`POST /api/organizations/platform/{id}/review` |
| 需求管理 | `POST/GET /api/demands/organizations/{id}`、`PUT /revisions/{revision}`、`POST /submit`、`POST /publish` |
| 平台需求审核 | `GET /api/demands/platform/pending`、`POST /api/demands/platform/{id}/review` |
| 开放需求 | `GET /api/demands/open-for-bid` |
| BP-04 应标/选标/确认 | `/api/bidding/demands/{id}/bids`、`/awards`、`/confirmation`、`/snapshots` |

接口响应使用后端统一 `Result<T>`；失败时显示 `message` 和可用的 `requestId`，不在浏览器端伪造权限或错误状态。
