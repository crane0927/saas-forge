# saas-forge 套餐、功能与配额设计

## Plan

Plan 是 SaaS 产品面向客户销售或授权的套餐，例如 LIS 的试用版、基础版、专业版和旗舰版。一个部署实例只承载一个产品，因此 Plan 不属于 Application。Plan 关联 Feature、Quota、套餐配置和套餐状态。`ACTIVE` Plan 的配置更新只影响之后创建的 Subscription；既有 Subscription 使用自己的不可变权益快照，MVP 不引入 Plan 版本实体。

## Subscription

Subscription 表示 Tenant 当前订阅某 Plan 的关系及其生命周期：

```text
Tenant → Subscription → Plan
```

可描述订阅状态、生效时间、到期时间、试用期、自动续订、取消时间、暂停状态和套餐快照。到期由 `endsAt` 在权益判断时派生，不持久化为 `EXPIRED`；MVP 取消即时停止权益，不支持期末取消；其余生命周期规则见[核心领域契约](17-core-domain-contracts.md#subscription)。首期 Subscription 不等同于完整计费系统；先负责产品权益和生命周期，支付、账单、发票后续独立演进。

## Feature

Feature 是可被套餐控制的产品能力。例如：`lis.report`、`lis.quality-control`、`lis.statistics`、`lis.ai-assistant`、`lis.device-integration`，或 CRM 的 `crm.customer-import`、`crm.customer-export`、`crm.advanced-report`。

平台负责 Feature 定义、状态、Plan 与 Feature 关系、Tenant 实际权益和 Feature 校验，不负责业务功能本身的实现。

## Quota

Quota 控制资源使用额度，如最大用户数、最大组织数、最大设备数、最大项目数、存储空间、API 调用量、AI Token 和业务数据量。

例如 LIS 基础版可设 `max_users = 50`、`max_devices = 5`、`storage = 100 GB`；专业版可设 `max_users = 500`、`max_devices = 50`、`storage = 1 TB`。

模型区分 `Quota Definition`（额度类型配置）、`Quota Limit`（Plan 为 Definition 配置的上限）、`Quota Usage`（Tenant 当前已用量）和 `Quota Operation`（带 `operationId` 的扣减或释放记录）。SDK 提供 `check`、`consume`、`release` 和 `usage`；其一致性和计量方案以[核心领域契约](17-core-domain-contracts.md#quota)为准。

## 校验关系

Permission 回答“当前用户能不能执行此操作”；Feature 回答“当前 Tenant 是否购买或启用产品能力”。即使某 Role 有 `lis:ai:use`，Tenant 未购买 AI 模块时仍不能访问。因此业务请求可能同时要求：

```text
Feature Check + Permission Check
```
