# saas-forge 项目章程

## 项目基本信息

| 项目 | 内容 |
|---|---|
| 项目名称 | `saas-forge` |
| 项目类型 | 开源 SaaS 开发底座 / SaaS 开发平台 |
| 项目定位 | 面向单 SaaS 产品、多租户场景的通用基础平台 |
| 核心形态 | Server + Platform Console + Tenant Console + SDK + Starter |
| 目标用户 | SaaS 产品研发团队、软件公司、独立开发者 |
| 首期技术生态 | Java / Spring Boot |
| 开源属性 | 开源 |
| 业务属性 | 业务无关 |
| 部署模型 | 一个 `saas-forge` 实例对应一个 SaaS 产品 |
| 核心目标 | 让开发者专注业务开发，而不是重复建设 SaaS 基础设施 |

## 愿景与定位

`saas-forge` 是一个持续运行、持续升级、被业务系统长期依赖的 SaaS 基础设施；它不是生成项目后即退出生命周期的传统脚手架。

> **一个面向单 SaaS 产品、多租户模式的业务无关开源 SaaS 开发底座。**

英文定位：

> **An open-source, business-agnostic foundation for building single-product, multi-tenant SaaS applications.**

“单产品”指一个部署实例服务一个 SaaS 产品，而不是项目只能用于一种业务。不同团队可分别基于它构建 LIS、CRM、ERP、WMS、OA、AI SaaS、项目管理 SaaS 或营销 SaaS，并各自独立部署。

## 目标

项目要统一解决 SaaS 产品中的通用问题：

- 谁在访问、属于哪个租户、是否允许登录；
- 拥有哪些角色和权限；
- 租户订阅了什么套餐、套餐开放了哪些功能、还可使用多少资源；
- 租户是否过期；
- 一项操作由谁完成。

业务开发者应把主要精力投入产品领域问题，而非重复设计上述基础设施。

## 核心原则

1. **单产品、多租户**：`1 Deployment = 1 SaaS Product = N Tenants`。
2. **业务无关**：Core 只包含不同领域 SaaS 普遍需要的能力。
3. **平台能力与业务能力分离**：业务系统通过 SDK / API 接入，不将领域业务代码放入底座。
4. **可独立部署**：启动后可获得基础服务及管理控制台。
5. **SDK First**：核心能力可通过 API、SDK、Starter、SPI、Event 或 Webhook 集成；业务系统不直接访问内部数据库。
6. **可升级**：业务系统不应长期 fork 底座源码，应能相对平滑地从 1.x 升级到 2.x。
7. **默认安全**：租户隔离、身份鉴权、权限控制是基础设施能力；不能仅以调用方传入的 `tenantId` 作为安全边界。

## 非目标与边界

### 不承担的职责

- **不是多 SaaS 产品聚合平台**：一个实例不承载 CRM、ERP、WMS、LIS 等多个独立应用；Core 不引入 `Application`、`ApplicationService`、`ApplicationRepository`、`tenant_application` 或 `application_id` 等模型。
- **不是具体业务系统**：`Customer`、`Order`、`Patient`、`Specimen`、`Inventory`、`Invoice`、`Contract` 等领域对象不进入 Core。
- **不是传统后台模板**：不以 clone 后修改框架源码、继续往同一工程添加业务模块作为主模式。
- **不是单纯脚手架**：未来的 `saas-forge init` 只是辅助工具；平台本身提供持续运行的 SaaS 能力。
- **不是单纯 Java Library**：Java / Spring Boot 为首期生态；领域层不绑定 Java，后续可通过 API 或 SDK 接入其他技术栈。

### Core 准入规则

一项能力进入 Core 前必须同时满足：

1. SaaS 产品普遍需要；
2. 与具体业务领域无关；
3. 业务系统可通过标准扩展点使用，且无需修改 Core。

Tenant、Identity、RBAC、Plan、Subscription、Feature、Quota、Audit 满足该规则；LIS 标本、CRM 客户、ERP 订单、WMS 库存不满足。

## 典型使用场景

软件公司可将 `saas-forge` 与自身的 LIS 业务代码组合为云 LIS。平台管理员创建和管理医院等 Tenant，配置套餐、有效期、Feature、Quota 并查看平台审计；医院管理员管理组织、用户、角色、权限、套餐、配额和审计；医生、护士等最终用户使用由软件公司开发的 LIS 业务应用。前两层属于 `saas-forge`，LIS 具体业务归业务系统。

## 成功标准

- **SaaS 通用性**：同一套 Core 可支持 LIS、CRM、ERP、WMS、项目管理和 AI SaaS，而无需修改核心领域模型。
- **开发效率**：业务系统接入后可快速获得 Tenant、IAM、RBAC、Plan、Subscription、Feature、Quota、Audit 能力。
- **业务解耦**：Core 不理解 `Patient`、`Customer`、`Order`、`Inventory` 等业务对象仍可运行。
- **升级能力**：业务系统升级底座时无需大规模重构。
- **安全性**：最大限度避免租户数据泄漏、越权访问、Tenant Context 伪造和权限遗漏。

## 开源与文档要求

项目从第一天按正式开源项目建设，至少包含：`README.md`、`LICENSE`、`CONTRIBUTING.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md`、`CHANGELOG.md`、`ROADMAP.md`。

建议文档体系包括 `getting-started`、概念文档（tenant、identity、membership、organization、permission、plan、subscription、feature、quota）、架构文档（tenant-context、data-isolation、security、extension）、`sdk`、`api`、`deployment` 与 `development`。

## 对外表述

推荐中文介绍：

> **saas-forge 是一个面向单 SaaS 产品、多租户场景的业务无关开源 SaaS 开发底座，为 SaaS 产品提供租户、身份、组织、权限、套餐、订阅、功能、配额、审计以及 SDK 等通用能力。**

精简版：

> **saas-forge 是一个帮助开发者快速构建多租户 SaaS 产品的开源基础平台。**

推荐 Slogan：

> **Build SaaS, not SaaS infrastructure.**

> **开发 SaaS，而不是重复开发 SaaS 基础设施。**

备选：**Forge your SaaS. Focus on your business.**（构建你的 SaaS，专注你的业务。）
