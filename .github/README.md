# GitHub 协作配置

## 用途

存放 [Issue 模板](ISSUE_TEMPLATE/README.md)、[工作流](workflows/README.md)和 [PR 模板](PULL_REQUEST_TEMPLATE.md)，不存放应用代码。

## 必须遵守

- 模板与当前劳务供应链平台定位、贡献规范保持一致。
- 工作流变更检查触发条件、最小权限、执行环境和凭据使用；生产发布须单独确认准入条件。
- CI 能力以实际 YAML 为准，配置存在不代表已成功运行或生产部署可用。

## 禁止事项

- 禁止提交 Token、SSH 私钥、生产地址凭据或真实客户资料。
- 禁止为通过 CI 取消安全校验、质量门禁或未经审批扩大工作流权限。
- 禁止将模板中的计划、复选框或声明视为验收证据。

相关入口：[贡献指南](../CONTRIBUTING.md)、[后端验证说明](../backend/README.md)。
