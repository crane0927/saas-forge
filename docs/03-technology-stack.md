# saas-forge 技术栈

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现。版本以根 `pom.xml` 与实际依赖清单为唯一权威，本文不重复固定版本号以免漂移；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

## 技术基线

| 领域 | 选型 | 约束与用途 |
|---|---|---|
| JVM | JDK 17 | 当前仅支持 JDK 17 构建与运行，统一以 `release=17` 编译；CI 执行 JDK 17 完整 `verify` |
| 服务端 | Spring Boot 4.x、Spring Security | 版本跟随根 `pom.xml` 继承的 `spring-boot-starter-parent`（当前为 4.0.7），不在此固定补丁号；Spring Boot 4 要求 Java 17 及以上，满足运行时基线 |
| 构建 | Maven、Maven Wrapper | 所有服务、SDK 与 Starter 统一入口 |
| 持久化 | MyBatis 3 | 需要精确控制 PostgreSQL RLS 上下文、UUIDv7、游标分页和原子配额更新；不使用 MyBatis-Plus 或 JPA |
| 数据库 | PostgreSQL 18 | 首期唯一支持的关系型数据库；每个服务拥有独立逻辑数据库，并使用原生 `uuidv7()` 生成独立实体主键 |
| 数据迁移 | Flyway | 每个服务独立维护并随版本发布，迁移记录可审计 |
| 缓存与安全状态 | Redis | Token 黑名单、Refresh Token / 会话缓存、限流、验证码与短期登录保护；生产环境要求高可用 |
| 事件总线 | Apache Kafka | CloudEvents JSON 领域事件；Transactional Outbox 发布；生产要求 3 Broker、3 副本、`min.insync.replicas=2`、`acks=all` |
| 对象存储 | S3 兼容对象存储（**未实现**） | 设计上仅存放平台导出的临时结果文件，业务附件由业务模块管理。按 [ADR 0036](adr/0036-tenant-access-owns-controlled-tenant-brand-profiles.md)：第 4 阶段随 Tenant 品牌素材引入最小 S3 能力，第 6 阶段的 Audit 导出在**分离的**存储边界、权限与生命周期策略内复用。仓库当前无对象存储依赖、镜像或实现 |
| 前端 | TypeScript、Vue 3、Element Plus、Vite、Pinia、Vue Router | Platform Console 与 Tenant Console Shell 为两个独立部署的应用；共享 `@saas-forge/admin`、`app-runtime`、`api-client` 与 `i18n`。Module Federation、Manifest 与业务 Remote 均**未实现** |
| 可观测性 | OpenTelemetry Collector（**仅此一项已部署**）、Prometheus、Loki、Tempo、Grafana | 当前 Compose 只运行 `otel/opentelemetry-collector` 且使用 `debug` exporter；Prometheus、Loki、Tempo、Grafana 与告警、SLO 审计均未部署 |
| 本地部署 | Docker Compose（集成验收工具） | 提供 `deploy/compose` 共享基础设施与 `deploy/acceptance` 组合验收；**不是日常应用启停入口**，日常开发按 [ADR 0043](adr/0043-native-local-development-is-separate-from-environment-orchestration.md) 与 [原生开发总入口](native-local-development.md) 执行 |
| 生产部署 | Kubernetes、Helm | 标准生产交付；同时提供虚拟机裸部署说明与 `systemd` 示例。`deploy/helm` 当前只有接入契约文档，**尚无可用 Chart** |

## 服务通信

- 外部 API、前端与业务系统集成：REST + JSON，契约为 OpenAPI 3.1。
- 平台内部需要即时结果的服务调用：gRPC + Protobuf。
- 状态变更通知、审计投递和缓存失效：Kafka；事件使用 CloudEvents JSON 与带版本的类型名。
- 所有生产通信使用 TLS；服务间 gRPC 使用 mTLS。

## 构建与依赖边界

- 服务间不共享领域代码、实体或数据库模型。
- 可共享的内容仅限版本化 OpenAPI / Protobuf / 事件契约、Redis 安全基础设施契约、日志 Schema、通用安全与可观测性库、构建 BOM；契约类型在服务边界映射为各服务自己的内部模型。
- Java SDK 与 Starter 使用语义化版本并通过 BOM 锁定模块版本；破坏性变更仅进入主版本。
- SDK 与服务端均使用固定 Maven 3.9.14及发行包 SHA-256 校验的 Maven Wrapper；依赖、测试、覆盖率与发布约定见 [Maven 构建与制品发布](21-maven-build-and-release.md)。

## 可观测性与性能基线

- Gateway 与所有服务透传 W3C Trace Context；`traceId` 关联 Gateway、gRPC、Kafka 和审计链路。
- 除上传、导出和异步任务外，外部 API 的目标为 p95 ≤ 300 ms、p99 ≤ 1 s。
- 生产可用性 SLO 为月度 99.9%，以 Gateway 成功请求率和登录、关键只读操作黑盒探针共同审计。

## 明确不纳入首期的技术范围

- 不支持 MySQL 或其他关系型数据库兼容层。
- 不引入业务附件或通用业务文件存储。
- 不接入面向用户的外部 OAuth 身份提供方、OIDC、SSO、LDAP 或第三方登录。
- 不把平台绑定到特定云厂商、Service Mesh 或对象存储产品；生产实现应满足本文定义的接口和安全结果。
