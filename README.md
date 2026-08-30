# saas-forge

Build SaaS, not SaaS infrastructure.

saas-forge 是一个面向单 SaaS 产品、多租户场景的业务无关开源基础平台。它提供 Tenant、IAM、RBAC、套餐、订阅、功能、配额、审计与 SDK 等通用能力；具体业务始终属于独立的业务应用。

## 核心领域概念

领域上下文与权威术语入口见 [CONTEXT-MAP.md](CONTEXT-MAP.md)，以下仅为核心概念摘要：

| 概念 | 定义 | 归属与边界 |
|---|---|---|
| **Tenant** | SaaS 客户的逻辑隔离空间，具体业务含义由接入产品定义。持久状态为 `PENDING`、`ACTIVE`、`SUSPENDED`、`CLOSED`；`EXPIRED` 是根据 `expiresAt` 派生的访问结果，不是持久状态。 | 由 Tenant Access 管理。Tenant 不等于 Customer 或 Account，用户也不能通过请求参数自行指定 Tenant Context。 |
| **Identity** | IAM 管理的全局认证主体，以唯一的规范化邮箱识别，可以有显示名；同一个 Identity 可以通过不同 Membership 加入多个 Tenant。 | 由 IAM 管理。Identity 不等于 Tenant 用户、Membership 或 Role；机器服务认证主体使用 OAuth Client。 |
| **Membership** | 一个 Identity 加入某个 Tenant 的成员关系，决定该 Identity 能否在该 Tenant 建立 Tenant Context，并可被启用或禁用。 | 由 Tenant Access 管理。Tenant Role 绑定 Membership，而不是全局 Identity；同一个 Identity 在不同 Tenant 中可以拥有不同 Role。Membership 不等于 Tenant Account 或用户与 Tenant 的简单映射。 |
| **RBAC** | 首期访问控制模型。租户范围内的授权链为 `Membership → Role → Permission`。 | 平台 Role 与 Tenant Role 严格隔离；Tenant Role 绑定 Membership，而不是全局 Identity。Tenant Administrator 是系统管理的 Tenant Role，不是超级用户标记。 |
| **Entitlement** | 平台的产品权益领域，用于判断某个 Tenant 当前拥有哪些产品能力、额度上限是多少，以及是否允许继续消耗额度。 | 由 Entitlement Service 管理，包含 Plan、Subscription、Feature 与 Quota；不负责认证、RBAC、Tenant 生命周期或完整计费。 |
| **Plan** | 产品向 Tenant 提供的 Feature 与 Quota 组合，即套餐模板。 | 由 Entitlement 管理。Plan 是新 Subscription 权益快照的来源，修改 Plan 不会回溯改变已有 Subscription。 |
| **Subscription** | Tenant 在一段有效期内获得指定 Plan 权益的关系。 | 由 Entitlement 管理。它表达产品权益生命周期，不等于合同、账单或完整计费系统；套餐变更产生新的不可变 Subscription Version。 |
| **Feature** | 可由 Plan 授予的产品能力。 | 由 Entitlement 管理。Feature 判断 Tenant 是否拥有或启用了某项产品能力；全局禁用会覆盖 Subscription 快照中的同名权益。 |
| **Quota** | 资源使用额度的总称，由 Quota Definition、Plan 中的 Quota Limit、Tenant 的 Quota Usage 和幂等的 Quota Operation 组成。 | 由 Entitlement 管理。Quota Usage 是权威已用量；`consume`、`release` 使用稳定的 `operationId` 保证计量重试幂等。 |
| **Audit** | 根据已经提交的领域事实，只追加保存的合规与业务追责记录，正式领域对象为 Audit Record。 | 由 Audit Service 管理。Audit 不是应用日志，也不负责裁决来源工作流是否成功。 |

核心关系如下：

```text
Identity ── Membership ── Tenant
                 │
                 └── Role ── Permission

Plan ── Feature
  └── Quota Definition + Limit
           │
Tenant ── Subscription Version
           └── 权益快照 ── Feature / Quota

已提交领域事实 ──> Audit Record
```

其中，RBAC 回答“当前成员能不能执行这个操作”，Entitlement 回答“当前 Tenant 有没有这项产品权益、还能使用多少”。一次业务请求可能需要同时通过 Tenant 可访问性、Feature、Quota 和 Permission 校验。

## 当前状态

当前仓库处于分阶段实现期：Maven 多模块构建、Gateway、四个领域服务、SDK/Starter、契约目录和最小 Docker Compose 运行拓扑均已建立；IAM、Tenant Access 与 Entitlement 已落地部分领域切片。通用 Tenant RBAC、Feature 运行时闭环和 Audit 业务能力仍未完整实现，领域定义不代表对应功能已经全部交付。

## 构建

后端运行环境为 JDK 17，CI 额外使用 JDK 21 验证兼容性。全仓库验证还要求 Node 24.14.1、pnpm 11.22.0，并先在 `consoles` 完成冻结 lockfile 安装；Maven 不安装前端工具或依赖。

```bash
cd consoles
corepack enable
pnpm install --frozen-lockfile
cd ..
./mvnw verify
```

`./mvnw verify` 会正式生成 TypeScript API Client，并调用一次前端聚合门禁；前端开发、独立构建和根命令详见 [consoles/README.md](consoles/README.md)。

## 目录

- gateway/：唯一公网入口模块。
- services/：IAM、Tenant Access、Entitlement 与 Audit 服务。
- contracts/：OpenAPI、Protobuf 与事件契约。
- sdk/：Java SDK、BOM 与 Spring Boot Starter。
- consoles/：Platform Console、Tenant Console Shell、业务 Remote 与共享前端边界。
- examples/：官方示例的预留位置。
- deploy/：Compose、Helm 与 systemd 交付物的预留位置。

## 本地 Compose

最小运行拓扑包含 Gateway、四个领域服务、PostgreSQL、Redis、Kafka、OpenTelemetry Collector 与四个 Flyway 迁移任务；对象存储将在第 6 阶段加入。使用方式见 [deploy/README.md](deploy/README.md)。

详细的产品、领域、架构、安全与部署约束见 docs/。

## 参与和安全

贡献方式见 CONTRIBUTING.md。安全问题请遵循 SECURITY.md，不要通过公开 Issue 披露。

本项目采用 Apache License 2.0。
