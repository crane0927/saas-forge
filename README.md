# saas-forge

Build SaaS, not SaaS infrastructure.

saas-forge 是一个面向单 SaaS 产品、多租户场景的业务无关开源基础平台。它提供 Tenant、IAM、RBAC、套餐、订阅、功能、配额、审计与 SDK 等通用能力；具体业务始终属于独立的业务应用。

## 当前状态

当前仓库处于项目骨架阶段：Maven 多模块构建、Gateway 与四个领域服务的启动验证、SDK/Starter 制品坐标、契约目录、控制台与部署目录边界均已建立。尚未实现 API、领域规则、数据存储、前端构建、Docker Compose 或官方业务示例。

## 构建

运行环境为 JDK 17；CI 额外使用 JDK 21 验证兼容性。

    ./mvnw verify

## 目录

- gateway/：唯一公网入口模块。
- services/：IAM、Tenant Access、Entitlement 与 Audit 服务。
- contracts/：OpenAPI、Protobuf 与事件契约。
- sdk/：Java SDK、BOM 与 Spring Boot Starter。
- consoles/：Platform Console、Tenant Console Shell、业务 Remote 与共享前端边界。
- examples/：官方示例的预留位置。
- deploy/：Compose、Helm 与 systemd 交付物的预留位置。

详细的产品、领域、架构、安全与部署约束见 docs/。

## 参与和安全

贡献方式见 CONTRIBUTING.md。安全问题请遵循 SECURITY.md，不要通过公开 Issue 披露。

本项目采用 Apache License 2.0。
