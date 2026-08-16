# saas-forge 领域模型

## 核心模型

核心领域收敛为：

```text
Platform
├── Tenant
├── Identity
├── Membership
├── Organization
├── Role
├── Permission
├── Plan
├── Subscription
├── Feature
├── Quota
└── Audit
```

核心关系：

```text
Platform
 ├── Plan ── Feature / Quota
 └── Tenant ── Subscription ── Plan

Identity ── Membership ── Tenant
Membership ── Role ── Permission
```

## Platform 与 Product Metadata

第一阶段中，一个 `saas-forge` 部署实例等于一个 Platform，表示当前 SaaS 产品的全局范围。平台级能力包括 Tenant、Plan、Subscription、平台管理员、平台权限、全局配置和审计。

运行中的产品可保留轻量产品元数据，例如：

```yaml
saas-forge:
  product:
    code: cloud-lis
    name: Cloud LIS
```

Product Metadata 用于产品名称展示、品牌、权限和 Feature 命名空间、平台标题及 SDK 标识；它不参与多应用管理，不要求独立数据表，不与 Tenant 建立多对多关系，也不是一级业务领域对象。

## Tenant

Tenant 是 SaaS 客户的逻辑隔离空间，其业务含义可为医院、企业、学校、门店集团、政府机构或个人工作空间，不绑定具体行业。

基础能力包括创建、修改、启用、停用、冻结、租户配置、有效期、生命周期和租户管理员。初步生命周期为：

```text
PENDING → ACTIVE → SUSPENDED
                 └→ EXPIRED → CLOSED
```

最终状态模型留待详细设计确定。

## Identity、Membership 与 Organization

Identity 表示平台中的自然人或机器身份，不能将 `User` 与 Tenant User 强耦合。一个 Identity 可加入多个 Tenant。

Membership 表示某 Identity 以成员身份加入某 Tenant；租户级 Role 应绑定 Membership，而非全局 Identity。这样同一用户可在 Tenant A 为管理员，在 Tenant B 为普通成员。

Organization 描述租户内部组织结构。底层应采用通用的 `Organization`、`OrganizationUnit`、`Membership` 体系，而非仅设计为特定的 `Department`。

## 访问控制模型

首期采用 RBAC：

```text
Membership → Role → Permission
```

平台权限与租户权限必须隔离。平台角色可拥有 `tenant:create`、`tenant:disable`、`plan:create`、`subscription:update`；租户角色可拥有 `system:user:create`、`system:role:update`。业务系统可注册如 `lis:specimen:list`、`lis:report:audit` 的权限，平台只负责定义、注册、绑定、授权和校验。

Permission 推荐命名为 `namespace:resource:action`，例如 `system:user:list`、`lis:report:audit`、`crm:customer:create`。`system` 是内置命名空间，业务系统自行维护自己的命名空间。

## 商业权益模型

- **Plan**：产品对客户销售或授权的套餐，关联 Feature、Quota、套餐配置和状态。
- **Subscription**：Tenant 当前订阅 Plan 的关系及生命周期，包含状态、生效/到期时间、试用期、自动续订、取消时间、暂停状态和套餐快照。首期只覆盖产品权益与生命周期，不等同于完整计费系统。
- **Feature**：可由套餐控制的产品能力。平台管理其定义、状态、Plan 关系、Tenant 实际权益及校验。
- **Quota**：资源使用额度，模型需考虑 `Quota Definition`、`Quota Limit`、`Quota Usage`；未来 SDK 提供 `check`、`consume`、`release`、`usage`。具体强一致、弱一致和计量方案待详细设计。

Permission 回答“当前用户能否执行操作”，Feature 回答“当前 Tenant 是否购买或启用产品能力”。一次业务请求可能同时需要 Feature Check 与 Permission Check。

## Audit

Audit 覆盖平台操作、租户管理、登录、权限、套餐订阅，以及业务系统上报的关键操作。记录建议包含 Tenant、Identity、Membership、Action、Resource、Request ID、IP、User Agent、Timestamp、Result 和 Metadata。

业务系统可使用：

```java
audit.log("lis.report.publish", reportId);
```

未来也可提供 `@Audit("lis.report.publish")` 注解。
