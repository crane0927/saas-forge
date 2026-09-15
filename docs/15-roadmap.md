# saas-forge 路线图

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

> **阶段编号说明**：本文使用 Phase 0–4 的**产品成熟度**编号，与 [MVP 开发计划](16-mvp-development-plan.md) 的 0–9 **实施阶段**编号是两套口径，不要混用。两套编号的合并尚未决定（见开放议题）。

## Phase 0：领域模型验证

完成 Tenant、Identity、Membership、IAM、RBAC、Plan、Subscription、Feature、Quota、Tenant Context 的验证。目标是先稳定核心概念，不急于开发大量页面。

**现状**：Tenant、Identity、Membership、认证/会话/授权、Plan、Quota、Tenant Context 已实现并落库；RBAC 只到 Tenant 内静态角色与角色绑定，通用 Permission 目录、Organization 与 Invitation 未实现；Feature 只有定义与套餐关联，运行时闭环未实现。

## Phase 1：核心 MVP

完成 Server、Platform Console、Tenant Console、Java SDK、Spring Boot Starter、Docker Compose 和 Example，跑通完整 SaaS 闭环。

**现状**：四个领域服务、Gateway、Java SDK 与 Spring Boot Starter 已实现；Platform Console 已覆盖 Tenant、Plan、Quota Definition、Subscription、管理员初始化与 OAuth Client 管理，Tenant Console 只有工作台。**Example、业务 Remote 与完整 SaaS 闭环尚未实现**，进度以 [MVP 开发计划](16-mvp-development-plan.md) 的阶段勾选为准。

## Phase 2：开发体验

优化 Starter、自动配置、注解、SDK、错误模型、文档、CLI 和 Quick Start，使普通 Spring Boot 开发者能在较短时间内接入 `saas-forge`。

**现状**：原生启动流程与分层验证文档已建立（见 [原生开发总入口](native-local-development.md)、[本地分层验证](local-verification.md)）；**CLI 与 Quick Start 命令尚未实现**。

## Phase 3：平台能力增强

逐步增加 API Key、OAuth 2.0、OIDC、SSO、Webhook、Event、更完整的 Quota、更完整的 Subscription 和租户生命周期自动化。

**现状**：**OAuth 2.0 与 Event 已交付**，不再属于未来项——OAuth 2.0 Client Credentials 签发/轮换/吊销与 OAuth Client 管理页面已实现（见 [OAuth Client Credentials 管理规格](22-oauth-client-credentials-management.md)），CloudEvents 领域事件与 Audit 成功事实消费已实现（见 [Audit 成功事实消费设计](24-audit-success-fact-consumption.md)）。**API Key、OIDC、SSO、Webhook 未实现**；Quota 与 Subscription 的高级能力、租户生命周期自动化仍未完成。

## Phase 4：生态建设

根据社区需求扩展 Node.js、Go、Python SDK，Kubernetes、Helm、Plugin 与 Extension Marketplace。

**现状**：**全部未实现**。`deploy/helm` 目前只有接入契约文档，没有任何 Chart。
