# saas-forge API 设计

## 集成原则

`saas-forge Server` 对外提供稳定 API。业务应用通过以下方式访问平台能力：

```text
REST API
SDK
Event
Webhook
```

业务系统禁止将直接访问 `saas-forge` 数据库作为正式集成方式。

## OpenAPI

Server 模块包含 `forge-openapi`，MVP 范围包含 OpenAPI。原始立项材料仅确定对外 API 的存在和集成边界，未定义资源端点、请求响应模型、错误码、认证协议、分页规则或版本策略；这些内容需在详细 API 设计阶段补齐。

## 业务能力注册

为保持业务无关且允许扩展，平台应考虑统一注册机制。业务系统启动时可注册：

```text
Permissions
Features
Quota Definitions
```

例如 LIS 注册 `lis:report:view`、`lis:report:audit`、`lis:report:publish`；`lis.quality-control`、`lis.statistics`、`lis.ai-assistant`；`lis.user.count`、`lis.device.count`、`lis.storage`。平台只保存和管理这些抽象定义。
