# saas-forge 租户架构

## 核心约束

```text
1 saas-forge Deployment
        =
1 SaaS Product
        =
N Tenants
```

一个 Tenant 是 SaaS 客户的逻辑隔离空间。Tenant 的行业语义由业务产品决定，可表示医院、企业、学校、门店集团、政府机构或个人工作空间。

## Tenant 生命周期与管理

平台支持创建、修改、启用、停用、冻结、租户配置、有效期、生命周期和管理员管理。Tenant 的持久状态、派生访问结果与唯一允许状态迁移见[核心领域契约](17-core-domain-contracts.md#tenant)；实现不得推定其中未列出的迁移。

## Tenant Context

租户上下文是关键基础设施。请求链路为：

```text
Request
  → Authentication
  → Identity
  → Membership
  → Tenant Resolve
  → Tenant Context
  → Permission / Feature / Quota
  → Business Logic
```

Tenant Context 必须可信、明确、不可由普通请求随意伪造，并支持跨线程安全传播、异步场景和消息场景。面向用户的 Tenant Context 只能由已验证 Access Token 的 `membershipId` 解析：用户请求不得通过请求头、查询参数、请求体或任何语义等价别名传入或覆盖 Tenant；出现这类输入时必须以 `400` 拒绝。

服务身份不建立 Tenant Context。Client Credentials 只用 `client_id` 与显式 `scope` 授权，不得伪造用户、Membership、Tenant 或用户 RBAC 上下文；缺少所需 scope 时必须以 `403` 拒绝。服务可在契约明确的内部调用或可信消息元数据中携带 Tenant Operation Target，但下游必须按该服务身份授权并校验目标 Tenant 与业务资源的关系，不能将其当作用户上下文。

业务系统不能将 `request.getTenantId()` 作为核心安全模型。推荐链路是：认证请求 → 可信 Tenant Context → 数据隔离，Tenant ID 由平台上下文决定。

## 数据隔离策略

长期支持的隔离模式：

| 模式 | 结构 | 阶段定位 |
|---|---|---|
| Shared Database + Shared Schema | 同一表以 `tenant_id` 区分记录 | 首版优先支持 |
| Schema Per Tenant | 每个 Tenant 一个 Schema | 后续能力 |
| Database Per Tenant | 每个 Tenant 一个数据库 | 面向更高隔离要求 |

首版无需实现全部模式，但核心抽象不得将 `tenant_id` 列永久写死为唯一隔离策略。
