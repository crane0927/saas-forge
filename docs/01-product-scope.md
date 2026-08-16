# saas-forge 产品范围

## 产品能力边界

`saas-forge` 负责 SaaS 通用能力：Tenant、用户与身份认证、组织、角色、权限、套餐、订阅、功能授权、配额、审计、平台管理、租户管理，以及业务系统的 SDK 接入。

业务系统只负责其领域业务。例如 LIS 中的患者、检验申请、标本、检验项目、检验结果、检验报告、仪器和质控均由 LIS 业务系统实现，不属于 `saas-forge`。

## 使用界面与用户

| 界面 | 使用者 | 核心职责 |
|---|---|---|
| Platform Console | SaaS 产品提供方 | 租户、套餐、订阅、Feature、Quota、平台管理员、平台权限、系统配置、平台审计 |
| Tenant Console | 租户管理员 | 组织、成员、用户、角色、权限、租户配置、套餐信息、配额使用、租户审计 |
| Business Application | 租户最终业务用户 | 由产品团队实现具体业务；使用平台提供的 SaaS 基础能力 |

Platform Console 的建议菜单为 Dashboard、租户管理、套餐管理、订阅管理、Feature 管理、Quota 管理、平台管理员、平台角色、系统设置和审计日志。租户管理需支持新增、修改、启用、冻结、停用、到期管理、管理员初始化、套餐分配、租户详情和使用情况。

Tenant Console 的建议菜单为 Dashboard、组织架构、成员管理、用户管理、角色管理、权限管理、套餐信息、Feature 权益、Quota 使用情况、租户设置和审计日志。

## MVP 范围

首版验证目标是：开发者能否基于平台快速构建真正具备多租户能力的 SaaS 产品。

MVP 包含：

- Tenant、Identity、Membership、Organization、IAM、RBAC、Permission；
- Plan、Subscription、Feature、Quota、Audit；
- Platform Console、Tenant Console、OpenAPI；
- Java SDK、Spring Boot Starter、Docker Compose 与官方 Example。

MVP 暂不包含完整支付系统、多语言 SDK 和所有租户数据库隔离方案，以避免范围失控。

## MVP 核心闭环

```text
部署 saas-forge
  → 平台管理员登录
  → 定义 Feature / Quota
  → 创建 Plan
  → 创建 Tenant
  → 创建 Subscription
  → 初始化 Tenant Admin
  → Tenant Admin 登录
  → 创建用户 / 组织 / Role
  → 业务服务接入 SDK
  → 解析 Tenant Context
  → Permission / Feature / Quota Check
  → 执行业务
  → Audit
```

该流程能自然、稳定地跑通，是核心模型成立的验证标准。

## 官方 Example

官方 Example 应避免直接使用 LIS、ERP 等复杂领域，建议提供只含 `Project`、`Task` 的 Project SaaS，用于演示：创建 Tenant、创建用户、分配 Role、注册业务 Permission / Feature / Quota、Tenant Context、数据隔离和审计。

示例套餐：

| Plan | Features | Quota |
|---|---|---|
| Free | `project.basic`；不含 `project.export` | `max_users = 5`，`max_projects = 10` |
| Professional | `project.basic`、`project.export`、`project.analytics` | `max_users = 100`，`max_projects = 1000` |

业务代码只需关心：

```java
@RequireFeature("project.export")
@RequirePermission("project:export")
public void export() {
}
```

## CLI

CLI 是开发体验工具，不是核心运行时。未来可提供 `saas-forge init cloud-lis`，按 Java、Spring Boot、项目名称及 Auth、Tenant、Permission、Feature、Quota、Audit 等模块创建接入平台的业务工程。生成后，业务项目仍长期依赖 `saas-forge` 服务和 SDK。
