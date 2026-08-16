# saas-forge 技术栈

## 首期技术方向

| 领域 | 初期方向 | 说明 |
|---|---|---|
| 后端 | Java、Spring Boot | 首期核心技术生态 |
| 安全框架 | Spring Security | 用于安全能力实现 |
| 数据访问 | MyBatis、MyBatis-Plus 或 JPA | 尚待进一步选型 |
| 数据库 | PostgreSQL 或 MySQL | 首期重点支持一个数据库，避免 MVP 同时维护多个兼容层 |
| 缓存 | Redis | 按实际需求引入 |
| 前端 | Vue 或 React | 用于 Platform Console 与 Tenant Console，具体方案待前端架构阶段确定 |
| 部署 | Docker、Docker Compose | 首期方案 |
| 后续部署 | Kubernetes、Helm | 后续演进 |

## 技术边界

Java / Spring Boot 是首期重点生态，不表示平台领域层绑定 Java。后续业务系统可通过 API 或 SDK 接入 Node.js、Go、Python、.NET、PHP 等技术栈。

## 非功能性技术目标

- 模块化：核心领域边界清晰；
- 可扩展：提供 SPI、Event、Webhook、Extension Point；
- 可升级：升级底座时不需重新整合业务系统；
- 可观测：提供 Logging、Metrics、Tracing、Health Check；
- 无状态：Server 尽可能支持水平扩容、负载均衡和高可用部署；
- 数据库版本管理：采用 Flyway 或 Liquibase 进行统一迁移。

> 数据访问实现、数据库类型、前端框架和迁移工具均为立项阶段的候选方向，尚未在原始材料中确定最终选型。
