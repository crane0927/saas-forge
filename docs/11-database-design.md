# saas-forge 数据库设计

## 数据库边界

首期唯一支持 PostgreSQL。四个领域服务分别拥有独立的逻辑数据库、独立账号组和独立 Flyway 迁移链路；可共用同一 PostgreSQL 集群，但禁止跨服务直连查询、共享表或共享迁移，也不得启用 FDW、`dblink` 或其他跨数据库访问机制。

```text
iam-service            → iam_db
tenant-access-service  → tenant_access_db
entitlement-service    → entitlement_db
audit-service          → audit_db
```

每个逻辑数据库使用两个仅属于该服务的账号：`*_migrator` 仅供 Flyway 执行迁移并拥有所属 Schema 中对象的所有权；`*_app` 仅供运行时服务连接，既不是表所有者，也不拥有 `BYPASSRLS`，只被授予所需的 DML 与 Schema `USAGE` 权限。运行时服务不得使用迁移账号；账号不得访问其他服务数据库。

每个逻辑数据库只使用默认 `public` Schema。集群引导必须撤销 `PUBLIC` 对数据库的默认权限及对 `public` 的 `CREATE` 权限；`*_migrator` 获得 `public` 的 `USAGE`、`CREATE`，`*_app` 只获得 `USAGE`。业务表、序列和 `flyway_schema_history` 均由 `*_migrator` 所有；每个建表迁移显式授予 `*_app` 所需的表 DML 与序列权限。运行时账号不得在任何 Schema 创建对象。

独立领域实体默认使用 PostgreSQL 18 原生 `uuidv7()` 生成 UUIDv7 主键，首个建表迁移应声明 `id uuid NOT NULL DEFAULT uuidv7()`；应用插入时不传入 `id`，以 `INSERT ... RETURNING` 取得生成值。API 以 UUID 字符串传输。字段是否存在由数据语义决定，不通过跨服务 `BaseEntity` 或共享持久化模型强制统一。

## 建模与命名规范

- 表、列使用小写 `snake_case`，表名使用复数；主键默认名为 `id`，外键列使用 `<目标实体单数>_id`。
- 主键、外键、唯一约束、检查约束和普通索引分别使用 `pk_`、`fk_`、`uq_`、`ck_`、`idx_` 前缀。
- 独立实体默认使用 UUIDv7；纯关联表可使用复合主键，不为统一形式虚构 `id`。
- 时间点使用 `timestamptz` 并按 UTC 处理；无时区日历日期使用 `date`。
- 金额与精确计量使用 `numeric`，不得使用浮点数；金额必须同时保存明确币种。
- 枚举保存为稳定字符串并使用 `CHECK` 约束，不使用 PostgreSQL 原生 ENUM。
- `jsonb` 仅用于结构确实开放的 Metadata，不替代需要查询、索引或约束的正式字段。
- 可空性、默认值和约束必须显式声明。数据库默认值只用于不依赖调用上下文的稳定技术值；领域默认值由领域逻辑决定。
- 外键和 `JOIN` 只允许引用同一服务、同一数据库中的表；禁止跨服务/跨数据库表引用、外键、`JOIN` 及查询封装。

### 公共字段适用矩阵

| 字段 | 适用规则 |
|---|---|
| `id` | 独立实体默认使用 UUIDv7；纯关联表允许使用复合主键 |
| `tenant_id` | Tenant 范围表必须非空；平台级和全局表不得为了形式统一而添加 |
| `created_at` | 实体及需要追踪创建时间的记录默认使用 |
| `updated_at` | 仅可变记录使用；不可变快照、Outbox 和 Audit Record 不使用 |
| `deleted_at` | 仅在已经确认软删除、恢复或留存查询需求时使用 |
| `status` | 仅存在明确生命周期的实体使用 |

不使用通用 `deleted` 布尔字段；它缺少删除时间，并容易与 `status` 产生双重事实。`created_by`、`updated_by` 等操作者字段按已证明的审计或查询需求逐表评审，不设为默认字段。

## Transactional Outbox 与消费去重

服务首次生产或消费已提交事实事件时，在自己的数据库和 Flyway 迁移链中分别建立本地 Outbox、消费去重和（需要时）隔离记录；不得预建没有业务切片的表，也不得在 SDK、契约模块或其他服务创建共享实现。Outbox 保存事务内生成的不可变完整 CloudEvents 快照，消费去重以 `(consumer_name, event_id)` 为唯一约束并与业务副作用同一事务提交。字段、索引、租约和保留要求见[事件工程约定](../contracts/events/transactional-outbox.md)。

## 逻辑数据模型

| 数据库 | 主要表 | 关键关系与约束 |
|---|---|---|
| `iam_db` | `identities`、`credentials`、`refresh_tokens`、`oauth_clients`、`oauth_client_secrets`、`signing_key_metadata` | `identities.email` 规范化后全局唯一；密码仅保存 Argon2id 哈希；Refresh Token 和 Client Secret 仅保存哈希；已轮换 Refresh Token 的摘要保留至其 Family 绝对到期后才可清理；`oauth_clients.allowed_scopes` 使用受限 `text[]`，MVP 仅允许 `runtime:read`、`runtime:quota:write`；`signing_key_metadata` 保存唯一 `kid`、KMS/HSM Key Version 引用、JWKS 的公开 `n`/`e` 与生命周期时间，私钥不入库 |
| `tenant_access_db` | `tenants`、`memberships`、`organizations`、`organization_units`、`roles`、`permissions`、`role_permissions`、`membership_roles`、`invitations`、`capability_registrations` | Membership 唯一关联 Identity 与 Tenant；Role 绑定 Membership；权限按命名空间、资源、动作定义；邀请保存一次性、限时激活状态 |
| `entitlement_db` | `plans`、`plan_features`、`plan_quotas`、`subscriptions`、`subscription_entitlement_snapshots`、`quota_definitions`、`quota_usages`、`quota_operations` | 一个 Tenant 任一时刻仅一个生效 Subscription；套餐变更产生新的订阅版本和不可变权益快照；`quota_operations.operation_id` 为全局唯一 UUIDv7，保证计量幂等 |
| `audit_db` | `audit_records`、`export_jobs` | `audit_records` 只追加，记录 Tenant、Identity、Membership、Action、Resource、Request ID、IP、User Agent、Timestamp、Result、Metadata；`export_jobs` 仅保存任务元数据，不保存导出结果文件 |

具体字段、枚举与 OpenAPI / Protobuf Schema 须在实现前同步评审；任一服务不得以外键约束、`JOIN`、FDW、`dblink` 或其他跨数据库访问机制耦合另一服务数据库。

`audit_app` 对 `audit_records` 只被授予 `SELECT`、`INSERT`，不得获得 `UPDATE`、`DELETE`、`TRUNCATE`，也不使用软删除；创建该表的迁移必须显式维持此权限。`export_jobs` 是可变任务元数据，按其状态迁移所需权限单独授予。迁移账号保留架构演进责任，但不得修改已进入主分支或发布版本的迁移。

## 多租户隔离与 RLS

Tenant 范围内的表必须有非空 `tenant_id`，并同时启用 `ENABLE ROW LEVEL SECURITY` 与 `FORCE ROW LEVEL SECURITY`。每个请求事务由数据访问层设置事务级上下文：

```sql
SELECT set_config('app.tenant_id', :tenant_id, true);
```

RLS 策略读取该值，默认拒绝缺失、非法 Tenant 上下文的读写。常规业务数据库角色不是表所有者，不得拥有 `BYPASSRLS`。

业务 RLS 策略只授予 `*_app`，并以事务级 `app.tenant_id` 同时限制 `USING` 与 `WITH CHECK`；`*_app` 不得继承或 `SET ROLE` 为迁移账号。每个 Tenant 表另有仅授予 `*_migrator` 的维护策略 `USING (true) WITH CHECK (true)`，供受控迁移任务执行跨 Tenant 数据回填。迁移账号凭据不得进入应用进程或常规业务请求路径。

Tenant 范围内的唯一约束、主要索引和相互引用按 `tenant_id` 限定作用域。Tenant 范围内的外键关系应包含 `tenant_id`，让数据库约束本身阻止跨 Tenant 引用。

平台跨 Tenant 管理只能经显式、可审计的平台服务操作完成，并使用受限的事务级平台上下文；该路径不得被常规业务请求或数据库账号调用。应用层可信 Tenant Context 与 RLS 同时生效，不能以任何一方替代另一方。

平台服务操作必须先验证 Platform 形态的 User Access Token，并通过 IAM 权威复核所需 Platform Role；只有复核成功后，服务才能把服务器生成或规范路径定位的 Tenant ID 作为 Tenant Operation Target，使用事务级 `set_config('app.tenant_id', ..., true)`。创建 Tenant 时由服务先生成 UUIDv7，再设置同一目标并插入；后台 Worker 只能从已持久化的权威工作流记录恢复目标。请求 Header、Body 或查询参数不得直接设置数据库 Tenant 上下文，`*_app` 仍不得获得 `BYPASSRLS`、迁移角色或通用跨 Tenant SQL 权限。

IAM 查询一个 Identity 的 Accessible Membership 可使用仅返回固定白名单字段、按 Identity 限定且只授予 `tenant_access_app` 执行权的 `SECURITY DEFINER` 函数；调用进入 Tenant Access 前必须验证 IAM 的 Service Access Token 与 `tenant-access:membership:read`。该函数不构成通用跨 Tenant 查询能力，其所有者、固定 `search_path`、PUBLIC 撤权、输入约束和最大返回数量必须由迁移与集成测试验证。

首期仅实现共享数据库、共享 Schema、`tenant_id` 隔离。Schema Per Tenant 与 Database Per Tenant 是后续演进方向，但当前核心模型不把 `tenant_id` 作为唯一永久隔离策略。

## 索引与一致性规则

- 所有 Tenant 范围查询的复合索引以 `tenant_id` 为首列，再按查询的状态、创建时间或业务键排序。
- 列表接口的游标分页索引必须匹配稳定排序键；推荐 `(tenant_id, created_at, id)`。
- Identity 邮箱、Permission 编码、Plan 编码、Feature 编码、Quota 定义编码、邀请令牌哈希和 Client Secret 哈希建立唯一约束或唯一索引。
- Subscription 使用部分唯一约束保证每 Tenant 仅一个当前 `TRIALING` 或 `ACTIVE` 版本；创建、套餐变更和自然到期后的重新订阅在同一事务中将旧版本转为 `SUPERSEDED`、创建新版本并写入新权益快照，避免双重权益窗口。
- `quota_usages` 使用 `(tenant_id, quota_definition_id)` 唯一约束；MVP `consume` 以 PostgreSQL 单条条件更新在 `used < limit` 时才将用量加一，保证并发不超额；Redis 不保存或裁决额度真相。`release` 的原子语义见核心领域契约。
- Audit 查询按 `tenant_id`、时间、Action、Identity 和 Resource 建立面向审计检索的索引；保留期由平台级合规配置决定。

## 迁移、备份与恢复

- 集群引导工件由受控的 PostgreSQL 管理账号执行，负责创建四个逻辑数据库、各库的 `*_migrator` / `*_app` 账号、Schema 基础权限及最小授权。它不属于任何领域服务，且不承载业务表结构或数据迁移；本地 Compose 与生产数据库运行方均执行等价的引导流程。
- 每个平台服务和官方 Example 在自己的 `src/main/resources/db/migration` 下维护独立 Flyway 迁移链；迁移与服务版本一起发布，不共享脚本或迁移历史。
- Flyway 只使用所属服务的 `*_migrator` 连接既有数据库；运行时服务只使用对应 `*_app` 账号。Flyway 和服务配置不得持有其他服务数据库或集群管理凭据。
- Flyway 必须由每个服务独立的一次性迁移任务执行：本地 Compose 使用 one-shot 容器，生产使用部署前 Job；迁移成功是对应应用启动的前提。常驻应用禁用 Flyway 自动迁移，且不得获取 `*_migrator` 凭据。
- 版本迁移使用 `V<版本>__<描述>.sql`。已进入主分支或发布版本的迁移禁止修改、删除和重排。
- 生产变更以前向修复为主。应用可以回滚，但不得假设数据库能够自动降级；不把 Flyway Undo 作为标准发布机制。
- Repeatable Migration 只用于可安全重复构建的视图、函数等对象，不用于表结构和业务数据。
- 数据回填与破坏性变更采用 expand → migrate → contract，兼容窗口和清理条件必须在变更中明确。
- 生产数据库由应用 Helm Chart 外部提供；需满足 RPO ≤ 5 分钟、RTO ≤ 30 分钟，并通过定期恢复演练验证。
- 备份、恢复、账号授权和高可用由数据库运行方负责；服务只通过受限账号访问自己的数据库。
- 导出结果文件位于 S3 兼容对象存储，不属于数据库备份对象；任务元数据和审计记录仍在 `audit_db`。

## MyBatis SQL 边界

- 表结构和数据迁移 SQL 只存在于所属服务的 Flyway 文件。
- 运行时查询与写入 SQL 只存在于所属服务的 Mapper XML；Mapper 接口只声明方法和类型契约。
- 禁止使用 `@Select`、`@Insert`、`@Update`、`@Delete` 以及 Provider 注解定义 SQL。
- 通过 `RETURNING` 返回结果集的 `INSERT`、`UPDATE` 或 `DELETE` 必须映射为 `<select affectData="true" flushCache="true">`，确保 MyBatis 按数据变更控制事务并使查询缓存失效。
- SDK、契约模块和跨服务公共模块不得包含 Mapper、Mapper XML、持久化 Entity 或迁移脚本。
- 用户可在自己拥有的单个服务和数据库边界内选择是否定义、继承本地持久化基类；SaaS Forge SDK 不发布持久化实体基类。

## 自动校验

当前仓库的 `./mvnw verify` 校验 Flyway 文件位置与命名、Repeatable Migration 的用途、MyBatis 注解 SQL 禁令、`RETURNING` 型 DML 的数据变更属性、Mapper 接口/XML 映射一致性、公共模块持久化类型禁令以及服务依赖边界。首个持久化实现必须同步加入真实 PostgreSQL 18 / Flyway / RLS Testcontainers 测试：集群引导创建四个逻辑数据库、八个账号与最小授权；四条迁移链只能由各自迁移账号执行；运行时账号不能跨库连接、创建对象或绕过 RLS；独立实体默认生成 UUIDv7；`audit_app` 不能更新或删除审计记录；并覆盖 Tenant A 无法读写改删 Tenant B 与无上下文默认拒绝。
