# saas-forge 数据库设计

## 数据库选型

首期重点支持一个数据库，候选为 PostgreSQL 或 MySQL；MVP 阶段避免同时维护多个数据库兼容层。最终选型尚未在立项材料中确定。

## 多租户隔离

| 模式 | 描述 | 阶段定位 |
|---|---|---|
| Shared Database + Shared Schema | 同一数据库、同一表，以 `tenant_id` 区分 Tenant 数据 | 首版优先支持 |
| Schema Per Tenant | 每个 Tenant 使用独立 Schema | 后续能力 |
| Database Per Tenant | 每个 Tenant 使用独立数据库 | 适用于更高隔离要求 |

首版不必实现全部模式，但核心抽象不能永久将 `tenant_id` 列写死为唯一策略。

## 版本管理

使用 Flyway 或 Liquibase 统一管理数据库迁移。

## 待补充内容

原始材料没有定义逻辑/物理数据模型、数据表、字段、主外键、索引、唯一约束、分库路由或备份恢复策略。这些需在后续数据库详细设计中补齐。
