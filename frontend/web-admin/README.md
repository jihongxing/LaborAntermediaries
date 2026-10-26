# 劳务供需平台 Web

面向访客、组织成员和平台岗位的 Web 入口，使用 Vue 3、TypeScript、Vue Router、Vite、Element Plus 和 Axios。
根路径 `/` 是公开门户；本地开发预览另提供匿名需求详情、未认证账号状态和用工方/供应商组织空间。

## 运行边界

- 后端在 `AUTH_ENABLED=true` 时提供手机号认证、Mock SMS、RS256 Access Token 和 Refresh Cookie；真实短信和真实微信适配器尚未接入。
- Access Token 和当前组织编号只保存在页面内存；Refresh Token 使用 HttpOnly Cookie，前端界面隐藏不能代替后端权限校验。
- `/login`、`/register`、`/password-recovery`、`/account` 和 `/workspace/:organizationId` 是真实认证入口，组织准入和成员权限仍由后端重新校验。
- 从受保护路由进入登录页时，成功登录后只接受站内 `redirect` 路径并返回原目标；平台岗位和组织权限仍在目标路由及服务端重新校验。
- 供应商开放需求只来自后端按租户生成的投递快照，页面不直接请求其他组织的需求源数据。
- 公共门户中的需求是本地开发预览用的合成样例，生产构建不包含该样例；当前后端没有公共需求授权、匿名投影或公开查询 API，未接入前不得对外展示业务需求。
- `/__dev/workspace/employer` 和 `/__dev/workspace/supplier` 仅在 Vite 开发模式注册，用于评审页面结构；它们使用演示状态/数据，不代表真实业务处理已接通。
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

开发服务器将 `/api` 代理到 `http://127.0.0.1:8080`；可通过 `.env.local` 的 `VITE_DEV_API_TARGET` 覆盖。

API 基地址可通过 `VITE_API_BASE_URL` 配置，默认是同源 `/api`。公开配置示例见 [.env.example](.env.example)，不要把真实令牌写入 `.env` 或源码。

## 验证

```powershell
npm run type-check
npm run build
npm run lint
```

`@playwright/test` 已作为开发依赖安装。浏览器手工验收使用仓库 `/playwright` 技能提供的 CLI 流程；不要把令牌、验证码或真实业务数据写入仓库测试夹具。

认证页面联调前需在后端 `local` profile 打开 `AUTH_ENABLED=true`、`AUTH_MOCK_SMS_ENABLED=true`，配置独立 RSA 密钥、手机号 HMAC 密钥和数据加密密钥；生产 profile 不可启用 Mock SMS。开发页只在成功发起挑战后通过 loopback Mock SMS 邮箱展示合成验证码。

## 页面与接口对应

门户披露字段和页面分层见[劳务供需门户与 Web 角色入口原型](../../docs/02-开发阶段/产品设计/00-产品全景/劳务供需门户与Web角色入口原型-v0.1.md)及[需求公开可见性标准](../../docs/02-开发阶段/产品设计/01-身份与需求交易/用工方入驻与有效需求标准-v0.1.md#741-公共门户与需求分阶段披露新增建议待业务规则评审)。

| 页面区域 | 主要接口 |
| --- | --- |
| 组织入驻 | `POST/GET/PUT /api/organizations`、`POST /submit` |
| 组织加入 | `POST /api/organizations/{id}/join`、`GET/POST /membership-requests` |
| 平台组织审核 | `GET /api/organizations/platform/pending`、`POST /api/organizations/platform/{id}/review` |
| 组织审核历史与申请方异议 | `GET /api/organizations/{organizationId}/review-history`、`POST /api/review-decisions/{decisionId}/appeals` |
| 需求管理 | `POST/GET /api/demands/organizations/{id}`、`PUT /revisions/{revision}`、`POST /submit`、`POST /publish` |
| 平台需求审核 | `GET /api/demands/platform/pending`、`POST /api/demands/platform/{id}/review` |
| 需求审核历史与申请方异议 | `GET /api/demands/{demandId}/review-history`、`POST /api/review-decisions/{decisionId}/appeals` |
| 开放需求 | `GET /api/demands/open-for-bid` |
| BP-04 应标/选标/确认 | `/api/bidding/demands/{id}/bids`、`/awards`、`/confirmation`、`/snapshots` |
| 平台监察查询 | `GET /api/platform/audit`、`GET /api/platform/audit/requests/{reviewCaseId}` |
| 本人异常恢复通知 | `GET /api/platform/tasks/recovery-notices`；仅当前认证账号可读 |
| 审计覆盖目录 | `GET /api/platform/audit/coverage` |
| 监察发现闭环 | `GET/POST /api/platform/audit/findings`、`GET /api/platform/audit/findings/{id}`、`POST /claim`、`POST /response`、`POST /review` |

接口响应使用后端统一 `Result<T>`；失败时显示 `message` 和可用的 `requestId`，不在浏览器端伪造权限或错误状态。个人账号页展示原认领人的异常解除通知；通知只含任务编号、类型和时间，岗位被撤销后仍可通过个人账号读取，内部恢复理由和申请材料不在普通通知中展示。

申请方组织账号页和用工方组织空间读取各自审核历史，并可针对当前有效审核决定提交一次独立复核申请；服务端校验事项、组织关系、版本和申请方身份，平台人员不能代替申请方创建。历史响应只返回申请方可见的版本、公开决定说明和异议状态（`PENDING`、`IN_REVIEW`、`REVIEWED` 或 `SUPERSEDED`），不返回复核员身份、内部理由或复核正文。独立复核结果只作为流程反馈，不能自动改写原组织/需求状态；页面会明确提示申请方继续按公开流程处理。

G9 监察页面对应 `/platform/audit`、`/platform/audit/coverage`、`/platform/audit/findings` 和 `/platform/audit/requests/:reviewCaseId`。这些路由要求登录后进入有效的平台监察上下文；前端守卫只负责导航体验，跨组织范围、事项历史、发现认领和复核仍由后端实时权限校验。G9、G10 已完成本地工程、API 和关键浏览器门禁；Web `type-check`、`lint`、`build` 与固定 Podman PostgreSQL `_test` 全量 Maven 门禁通过。浏览器已验证公开内容披露边界、普通无岗位账号 403，以及真实合成监察员的查询、覆盖和发现页面。G11 仅完成合成数据试点准备和授权撤销演练；生产岗位授权、真实 ClamAV、敏感原件下载/导出、法律/隐私专业评审、值守责任和单独启用决定仍未完成。
