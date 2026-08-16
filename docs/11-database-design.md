# saas-forge 数据库设计

## 数据库边界

首期唯一支持 PostgreSQL。四个领域服务分别拥有独立的逻辑数据库、独立数据库账号和独立 Flyway 迁移链路；可共用同一 PostgreSQL 集群，但禁止跨服务直连查询、共享表或共享迁移。

```text
iam-service            → iam_db
tenant-access-service  → tenant_access_db
entitlement-service    → entitlement_db
audit-service          → audit_db
```

所有领域实体使用 PostgreSQL 生成的 UUIDv7 主键，API 以 UUID 字符串传输。可变记录默认包含 `created_at`、`updated_at`、`status`，适用时包含 `deleted_at`；审计记录只追加，不修改、不物理删除。

## 逻辑数据模型

| 数据库 | 主要表 | 关键关系与约束 |
|---|---|---|
| `iam_db` | `identities`、`credentials`、`refresh_tokens`、`oauth_clients`、`oauth_client_secrets`、`signing_key_metadata` | `identities.email` 规范化后全局唯一；密码仅保存 Argon2id 哈希；Refresh Token 和 Client Secret 仅保存哈希；密钥私钥不入库，只记录轮换元数据 |
| `tenant_access_db` | `tenants`、`memberships`、`organizations`、`organization_units`、`roles`、`permissions`、`role_permissions`、`membership_roles`、`invitations`、`capability_registrations` | Membership 唯一关联 Identity 与 Tenant；Role 绑定 Membership；权限按命名空间、资源、动作定义；邀请保存一次性、限时激活状态 |
| `entitlement_db` | `plans`、`plan_features`、`plan_quotas`、`subscriptions`、`subscription_entitlement_snapshots`、`quota_definitions`、`quota_usages`、`quota_operations` | 一个 Tenant 任一时刻仅一个生效 Subscription；套餐变更产生新的订阅版本和不可变权益快照；`quota_operations.operation_id` 唯一保证幂等 |
| `audit_db` | `audit_records`、`export_jobs` | `audit_records` 只追加，记录 Tenant、Identity、Membership、Action、Resource、Request ID、IP、User Agent、Timestamp、Result、Metadata；`export_jobs` 仅保存任务元数据，不保存导出结果文件 |

具体字段、枚举与 OpenAPI / Protobuf Schema 须在实现前同步评审；任一服务不得以外键约束或 SQL Join 耦合另一服务数据库。

## 多租户隔离与 RLS

Tenant 范围内的表必须有非空 `tenant_id`，并启用 PostgreSQL Row-Level Security（RLS）。每个请求事务由数据访问层设置事务级上下文：

```sql
SELECT set_config('app.tenant_id', :tenant_id, true);
```

RLS 策略读取该值，默认拒绝缺失 Tenant 上下文的读写。常规业务数据库角色不得拥有 `BYPASSRLS`。

平台跨 Tenant 管理只能经显式、可审计的平台服务操作完成，并使用受限的事务级平台上下文；该路径不得被常规业务请求或数据库账号调用。应用层可信 Tenant Context 与 RLS 同时生效，不能以任何一方替代另一方。

首期仅实现共享数据库、共享 Schema、`tenant_id` 隔离。Schema Per Tenant 与 Database Per Tenant 是后续演进方向，但当前核心模型不把 `tenant_id` 作为唯一永久隔离策略。

## 索引与一致性规则

- 所有 Tenant 范围查询的复合索引以 `tenant_id` 为首列，再按查询的状态、创建时间或业务键排序。
- 列表接口的游标分页索引必须匹配稳定排序键；推荐 `(tenant_id, created_at, id)`。
- Identity 邮箱、Permission 编码、Plan 编码、Feature 编码、Quota 定义编码、邀请令牌哈希和 Client Secret 哈希建立唯一约束或唯一索引。
- Subscription 使用部分唯一约束保证每 Tenant 仅一个当前生效记录。
- `quota_usages` 使用 `(tenant_id, quota_definition_id)` 唯一约束；扣减与释放以单条条件更新或行锁原子执行，Redis 不作为额度真相来源。
- Audit 查询按 `tenant_id`、时间、Action、Identity 和 Resource 建立面向审计检索的索引；保留期由平台级合规配置决定。

## 迁移、备份与恢复

- 每个服务用 Flyway 维护单向、可审计的版本迁移；迁移与服务版本一起发布。
- 生产数据库由应用 Helm Chart 外部提供；需满足 RPO ≤ 5 分钟、RTO ≤ 30 分钟，并通过定期恢复演练验证。
- 备份、恢复、账号授权和高可用由数据库运行方负责；服务只通过受限账号访问自己的数据库。
- 导出结果文件位于 S3 兼容对象存储，不属于数据库备份对象；任务元数据和审计记录仍在 `audit_db`。
