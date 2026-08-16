# saas-forge 模块设计

## 仓库模块

初步目录建议：

```text
saas-forge
├── saas-forge-server
├── saas-forge-console
├── saas-forge-sdk
├── saas-forge-starters
├── saas-forge-cli
├── examples
├── docs
├── deploy
└── scripts
```

## Server 模块

```text
saas-forge-server
├── forge-core
├── forge-platform
├── forge-tenant
├── forge-iam
├── forge-organization
├── forge-rbac
├── forge-plan
├── forge-subscription
├── forge-feature
├── forge-quota
├── forge-audit
├── forge-openapi
└── forge-bootstrap
```

## 约束与待定项

模块划分以业务无关的领域边界为基础：平台与业务系统分离，业务通过 SDK/API 接入。模块粒度不是最终方案；详细划分需要通过 DDD、依赖方向和实际编码继续验证。

扩展能力的目标接口包括 SPI、Event、Webhook 与 Extension Point。
