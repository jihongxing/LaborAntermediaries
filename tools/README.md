# 项目辅助工具

## 用途与现状

存放开发、数据处理和文档生成工具，不承载线上业务接口或正式业务规则。

| 目录 | 职责与现状 |
| --- | --- |
| [backup/](backup/README.md) | 备份恢复工具预留，暂无实现。 |
| [code-generator/](code-generator/README.md) | 代码生成工具预留，暂无实现。 |
| [data-migration/](data-migration/README.md) | 数据迁移工具预留，暂无实现；不替代 Flyway。 |
| [customer_delivery_package/](customer_delivery_package/README.md) | 已有历史客户交付包生成脚本，运行会改写文档。 |

## 必须遵守

- 每个可执行工具说明依赖、输入输出、工作目录、副作用、失败处理和恢复方式。
- 使用合成或脱敏数据，生成结果人工复核后再纳入代码或文档体系。
- 运行前核查脚本实际路径和影响范围；部署设施归入 [infrastructure](../infrastructure/README.md)。

## 禁止事项

- 禁止把真实备份、凭据、缓存或生成临时文件提交到仓库。
- 禁止默认连接生产、静默覆盖正式资料或以工具结果绕过评审与审批。
- 禁止把历史 SaaS 模板输出视为当前平台需求或合同承诺。
