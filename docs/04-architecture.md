# saas-forge 总体架构

## 架构概览

`saas-forge` 是单产品 SaaS 的 Control Plane，业务应用是 Business Plane。前者管理租户、访问控制和产品权益；后者执行具体业务。

```text
Platform Console
        │
        ▼
saas-forge Server
  Tenant / IAM / Organization / RBAC
  Plan / Subscription / Feature / Quota / Audit / OpenAPI
        │
        ├──────── Tenant Console
        └──────── SDK / Starter / API ──── Business Application
```

一个标准部署实例由 `saas-forge` 与 Business App 构成：`saas-forge` 提供 SaaS 基础能力，业务应用提供具体业务能力；Platform Console 面向 SaaS 服务商，Tenant Console 面向租户管理员。二者通过 SDK / API 集成。

## Control Plane 与 Business Plane

| 平面 | 归属 | 负责的问题 |
|---|---|---|
| Control Plane | `saas-forge` | Who：谁在访问；Tenant：属于哪个租户；Can：可执行什么；Feature：是否有产品能力；Limit：还能使用多少资源；Subscription：订阅是否有效 |
| Business Plane | Business Application | What：具体执行什么业务 |

`saas-forge` 不内置 LIS、CRM、ERP、WMS 等领域功能；业务系统通过标准集成方式使用底座能力。

## 独立性与依赖方向

- `saas-forge` Server、控制台和业务服务可独立开发、部署、扩容和升级。
- 业务系统通过 API / SDK / Starter 接入平台，禁止以直接读取 `saas-forge` 数据库作为正式集成方式。
- 平台应独立运行；开发体验目标是执行 `docker compose up -d` 后获得基础服务和管理控制台。

## 与传统后台框架的关系

可借鉴若依类项目的开箱即用、后台管理、统一权限、基础模块和快速开发体验，但架构目标不同：传统后台框架趋于“基础后台 + 业务模块 = 一个工程”；本项目强调“独立 SaaS 基础平台 → SDK/API → 独立业务系统”，以降低长期耦合。

传统脚手架生成项目后退出生命周期；`saas-forge` 在整个 SaaS 运行周期内持续提供基础能力。CLI 即使存在，也只是脚手架工具，而不是平台本体。
