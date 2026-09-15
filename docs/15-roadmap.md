# saas-forge 路线图

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。

> **编号口径**：本仓库的实施阶段**只有一套编号**——[MVP 开发计划](16-mvp-development-plan.md) 的 0–9 阶段。本文原先另用 Phase 0–4 描述"产品成熟度"，其中 Phase 0–2 与该计划的 0–9 覆盖同一范围，两套编号并存只会互相漂移，故已删除并改为指向该计划。本文只保留 **MVP 交付之后、尚未排期** 的方向，使用 H1/H2 编号，与实施阶段刻意区分。

> **覆盖范围**：本文只给出两个成形的后续方向（H1 平台能力、H2 生态）。MVP 之后事项的完整枚举以 [MVP 开发计划](16-mvp-development-plan.md) 的「MVP 后续项」为准，本文字段不重复维护那份清单。

## H1：平台能力增强（尚未排期）

目标：逐步增加 API Key、OAuth 2.0、OIDC、SSO、Webhook、Event、更完整的 Quota、更完整的 Subscription 和租户生命周期自动化。

**范围修正**：**OAuth 2.0 与 Event 已交付，不属于本项范围**。OAuth 2.0 Client Credentials 的签发/轮换/吊销与 OAuth Client 管理页面已实现（见 [OAuth Client Credentials 管理规格](22-oauth-client-credentials-management.md)），CloudEvents 领域事件与 Audit 成功事实消费已实现（见 [Audit 成功事实消费设计](24-audit-success-fact-consumption.md)）。更早的路线图把它们列为未来项，属于历史口径，此处保留原始目标列表并就地更正。

**当前状态**：**API Key、OIDC、SSO、Webhook 未实现**；Quota 与 Subscription 的高级能力、租户生命周期自动化仍未完成。

## H2：生态建设（尚未排期）

目标：根据社区需求扩展 Node.js、Go、Python SDK，Kubernetes、Helm、Plugin 与 Extension Marketplace。

**当前状态**：**全部未实现**。`deploy/helm` 目前只有接入契约文档，没有任何 Chart。
