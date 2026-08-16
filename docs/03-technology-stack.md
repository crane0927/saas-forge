# saas-forge 技术栈

## 技术基线

| 领域 | 选型 | 约束与用途 |
|---|---|---|
| JVM | JDK 17 | 源码与最低运行版本为 JDK 17；CI 同时验证 JDK 17、JDK 21；禁止依赖 Java 21 专有 API |
| 服务端 | Spring Boot 4.1.x、Spring Security | 固定至 4.1 系列最新补丁；Spring Boot 4.1 要求 Java 17 及以上，满足运行时基线（[官方系统要求](https://docs.spring.io/spring-boot/system-requirements.html)） |
| 构建 | Maven、Maven Wrapper | 所有服务、SDK 与 Starter 统一入口 |
| 持久化 | MyBatis 3 | 需要精确控制 PostgreSQL RLS 上下文、UUIDv7、游标分页和原子配额更新；不使用 MyBatis-Plus 或 JPA |
| 数据库 | PostgreSQL | 首期唯一支持的关系型数据库；每个服务拥有独立逻辑数据库 |
| 数据迁移 | Flyway | 每个服务独立维护并随版本发布，迁移记录可审计 |
| 缓存与安全状态 | Redis | Token 黑名单、Refresh Token / 会话缓存、限流、验证码与短期登录保护；生产环境要求高可用 |
| 事件总线 | Apache Kafka | CloudEvents JSON 领域事件；Transactional Outbox 发布；生产要求 3 Broker、3 副本、`min.insync.replicas=2`、`acks=all` |
| 对象存储 | S3 兼容对象存储 | 仅存放平台导出的临时结果文件；业务附件由业务模块管理 |
| 前端 | TypeScript、React、Module Federation | Platform Console 独立应用；Tenant Console Shell 统一入口；业务前端以独立部署的远程模块接入 |
| 可观测性 | OpenTelemetry Collector、Prometheus、Loki、Tempo、Grafana | 统一 Trace、Metric、结构化日志、告警和 SLO 审计 |
| 本地部署 | Docker Compose | 本地开发、演示和端到端测试 |
| 生产部署 | Kubernetes、Helm | 标准生产交付；同时提供虚拟机裸部署说明与 `systemd` 示例 |

## 服务通信

- 外部 API、前端与业务系统集成：REST + JSON，契约为 OpenAPI 3.1。
- 平台内部需要即时结果的服务调用：gRPC + Protobuf。
- 状态变更通知、审计投递和缓存失效：Kafka；事件使用 CloudEvents JSON 与带版本的类型名。
- 所有生产通信使用 TLS；服务间 gRPC 使用 mTLS。

## 构建与依赖边界

- 服务间不共享领域代码、实体或数据库模型。
- 可共享的内容仅限版本化 OpenAPI / Protobuf 契约、通用安全与可观测性库、构建 BOM。
- Java SDK 与 Starter 使用语义化版本并通过 BOM 锁定模块版本；破坏性变更仅进入主版本。
- SDK 与服务端均使用 Maven Wrapper，避免开发机和 CI 的构建工具版本漂移。

## 可观测性与性能基线

- Gateway 与所有服务透传 W3C Trace Context；`traceId` 关联 Gateway、gRPC、Kafka 和审计链路。
- 除上传、导出和异步任务外，外部 API 的目标为 p95 ≤ 300 ms、p99 ≤ 1 s。
- 生产可用性 SLO 为月度 99.9%，以 Gateway 成功请求率和登录、关键只读操作黑盒探针共同审计。

## 明确不纳入首期的技术范围

- 不支持 MySQL 或其他关系型数据库兼容层。
- 不引入业务附件或通用业务文件存储。
- 不接入面向用户的外部 OAuth 身份提供方、OIDC、SSO、LDAP 或第三方登录。
- 不把平台绑定到特定云厂商、Service Mesh 或对象存储产品；生产实现应满足本文定义的接口和安全结果。
