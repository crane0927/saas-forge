# saas-forge 核心领域契约

本文只记录已评审并冻结的领域规则。未在本文明确的枚举值、状态迁移、幂等行为和错误码均不得由实现自行推定；需要新增时必须重新评审。

## 错误码

稳定业务错误码使用全大写 `UPPER_SNAKE_CASE`。领域错误必须以领域前缀命名，例如 `TENANT_INVALID_STATE_TRANSITION`、`SUBSCRIPTION_NOT_ACTIVE`、`QUOTA_EXCEEDED`；跨领域协议错误使用 `IDEMPOTENCY_*`。HTTP 状态表达错误类别，客户端以 `code` 分支，`detail` 仅供人读。

对已通过 Gateway 安全 Token 验证的 Tenant 内业务请求，必须先判定 Tenant 可访问性，再执行 Subscription、Feature、Quota 等后续校验。Tenant 为 `PENDING`、`SUSPENDED`、派生 `EXPIRED` 或 `CLOSED` 时，依次优先返回对应的 `TENANT_PENDING`、`TENANT_SUSPENDED`、`TENANT_EXPIRED` 或 `TENANT_CLOSED`；仅平台级 Tenant 管理操作，以及平台或内部工作流执行的 Quota `release` 清理，可跳过此顺序。签名、时间、Claim、`jti`/`kid` 撤销或 Revocation Fence 检查失败属于更早的认证拒绝，统一返回 `401 / ACCESS_TOKEN_INVALID`，不再暴露 Tenant 状态。

## 外部状态变更幂等

所有外部可见的创建和状态变更请求必须携带 `Idempotency-Key`；读取请求不要求该请求头。键按外部调用方跨全部状态变更接口唯一：用户令牌使用 `identityId`，服务令牌使用 `client_id`；未认证的 Invitation 激活请求在验证令牌后使用 `invitationId`。同键重试完全相同的请求时，服务原样重放首次完成请求的 HTTP 状态码和响应体，而不重新执行业务操作。

同一键若对应不同 HTTP 方法、规范化路径或规范化请求体，服务以 `409 Conflict` 和 `IDEMPOTENCY_KEY_REUSED` 拒绝，不重放也不执行业务操作。幂等记录自首次完成起保留 24 小时；期满后同一键可视为新请求。

首次请求尚未完成时，完全相同的同键并发请求以 `409 Conflict`、`IDEMPOTENCY_REQUEST_IN_PROGRESS` 和 `Retry-After` 立即拒绝；服务只保留一个执行者。客户端在完成后重试，取得首次响应。

首个业务 `4xx` 响应也是稳定结果，须在 24 小时窗口内原样重放。调用方修正请求时必须使用新幂等键。

仅 `2xx` 和业务 `4xx` 是可重放稳定结果。未形成持久完成记录的基础设施 `5xx` 不缓存，并释放键供调用方重试；任何已提交的业务变更必须与幂等完成记录在同一事务中写入。

尚未进入业务处理的请求格式或字段校验 `400` 不属于业务 `4xx`，不得创建幂等完成记录；调用方修正请求后可沿用原 `Idempotency-Key`。这包括非法邮箱、非法 `operationId`、无法解析的 Invitation 激活令牌，以及幂等键本身缺失、空白或格式非法。

必填键缺失或空白时，服务以 `400 Bad Request` 和 `IDEMPOTENCY_KEY_REQUIRED` 拒绝；格式非法时以 `400 Bad Request` 和 `IDEMPOTENCY_KEY_INVALID` 拒绝。两种情形均不预留或消耗键。

## 跨服务工作流幂等

跨服务流程不存在共享数据库事务或全局幂等表。接收外部状态变更请求的服务是流程根：它以外部调用方和 `Idempotency-Key` 唯一确定一个持久化工作流，并在该工作流中一次性分配、持久化子操作 ID。重试必须恢复同一工作流，不能创建新的子操作。

外部 `Idempotency-Key` 不跨服务透传。IAM 与 Entitlement 分别按调用服务和稳定子操作 ID 去重、重放结果或继续执行；Quota 仍以既有 `operationId` 契约为准。流程根必须将本地领域变更、稳定 HTTP 结果、Outbox 和尚未完成的补偿/重试工作项置于同一事务。工作流及其子操作 ID 必须保留至所有必需的前向操作或补偿完成；事件消费者再以 CloudEvents `id` 幂等消费。四条流程的根服务和恢复顺序见[跨服务工作流契约](18-tenant-access-cross-service-workflows.md)。

跨服务根工作流使用同步快路径与持久化后台恢复：HTTP 请求可立即推进，但必须在首次远程调用前持久化根状态、稳定子操作 ID 与下一步动作。请求超时、进程重启或依赖暂时不可用后，拥有该工作流的服务必须由后台 Worker 通过数据库租约继续同一流程；客户端同 Key 重试只查询、协助推进或重放结果，不是恢复前提。每个业务串行化键同时最多一个执行者，Worker 必须复用原子操作 ID；补偿和已提交业务后的投递即使客户端不再请求也持续执行。退避由环境配置，达到重试次数不得伪造业务成功。

## Subscription

Subscription 的到期由 `endsAt` 在权益判断时派生；`EXPIRED` 不属于其持久状态。实现不得依赖定时任务将 Subscription 改写为 `EXPIRED`，也不得在已到期但状态尚未刷新的窗口继续授予权益。

MVP 的取消立即停止权益，不支持在当前周期结束后再取消。因此 `CANCELED` 应表达无权益的终态，而非待生效的取消意图。

MVP 创建 Subscription 立即生效：按创建方式直接进入试用或生效状态，不支持未来 `startsAt` 的预订，也不引入 `PENDING` 状态。

### 持久状态

| 状态 | 权益语义 |
|---|---|
| `TRIALING` | 试用期内有效。 |
| `ACTIVE` | 正常有效。 |
| `PAUSED` | 暂停，不授予权益。 |
| `CANCELED` | 即时取消终态，不授予权益。 |
| `SUPERSEDED` | 已被套餐变更产生的新版本替代，不授予权益。 |

`trialEndsAt` 到达时自动执行 `TRIALING → ACTIVE`。该转换不得成为权益连续性的前提：未到 `endsAt` 的 `TRIALING` Subscription 在转换作业延迟时仍有效。

暂停不冻结 `trialEndsAt` 或 `endsAt`，也不延长有效期。恢复时，未到 `trialEndsAt` 的 Subscription 回到 `TRIALING`，否则回到 `ACTIVE`；达到 `endsAt` 时仍按到期规则拒绝权益。

`PAUSED` Subscription 不允许直接套餐变更，必须先恢复为 `TRIALING` 或 `ACTIVE`；暂停中仍可取消。

套餐变更只允许从未到 `endsAt` 的 `TRIALING` 或 `ACTIVE` 发起：旧版本转为 `SUPERSEDED`，新版本立即生效。已到 `endsAt` 的 Subscription 必须走重新订阅流程，由旧版本转为 `SUPERSEDED` 并创建新版本，不得作为存续期间的套餐变更处理。

每个 Tenant 仅可使用一次试用资格。试用期内套餐变更产生的新 Subscription 继承原 `trialEndsAt`，不得重新开始试用。

### 唯一允许的状态迁移

| 起始状态 | 目标状态 |
|---|---|
| `TRIALING` | `ACTIVE`、`PAUSED`、`CANCELED`、`SUPERSEDED` |
| `ACTIVE` | `PAUSED`、`CANCELED`、`SUPERSEDED` |
| `PAUSED` | `TRIALING`、`ACTIVE`、`CANCELED` |
| `CANCELED` | 无 |
| `SUPERSEDED` | 无 |

`TRIALING → ACTIVE` 由 `trialEndsAt` 自动触发；`PAUSED` 恢复目标按当时是否已过 `trialEndsAt` 确定。未列出的迁移一律拒绝；到期只影响派生权益结果，不增加持久状态迁移。

任一 Tenant 在任一时刻至多拥有一个授予权益的 Subscription，即未到 `endsAt` 且状态为 `TRIALING` 或 `ACTIVE` 的版本。创建、续订或套餐变更必须在同一业务事务中完成旧版本替换、新版本创建和新权益快照写入，不得留下双重权益窗口。自然到期后的重新订阅也按此模型执行：旧版本转为 `SUPERSEDED`，不原地复用。

Subscription 不存在时，服务返回 `404 Not Found` 和 `SUBSCRIPTION_NOT_FOUND`。

请求不在 Subscription 状态迁移矩阵中的变更时，服务返回 `409 Conflict` 和 `SUBSCRIPTION_INVALID_STATE_TRANSITION`。

对 `PAUSED` Subscription 发起套餐变更时，服务返回 `409 Conflict` 和 `SUBSCRIPTION_PLAN_CHANGE_REQUIRES_RESUMPTION`。

对已到 `endsAt` 的 Subscription 发起套餐变更时，服务返回 `409 Conflict` 和 `SUBSCRIPTION_PLAN_CHANGE_REQUIRES_RESUBSCRIPTION`。

Tenant 已消耗试用资格却请求创建 `TRIALING` Subscription 时，服务返回 `409 Conflict` 和 `SUBSCRIPTION_TRIAL_ALREADY_USED`。

## Plan

`ACTIVE` Plan 的 Feature、Quota 配置允许原地更新，但仅影响此后创建的 Subscription；既有 Subscription 始终读取自己的不可变权益快照。MVP 不引入 Plan 版本实体。

| 状态 | 语义 |
|---|---|
| `DRAFT` | 可编辑，不能创建 Subscription。 |
| `ACTIVE` | 可编辑，可创建 Subscription。 |
| `RETIRED` | 保留查询和审计，不能创建新的 Subscription。 |

`RETIRED` Plan 可显式恢复为 `ACTIVE`；恢复不会回溯改变任何已有 Subscription 快照。

`DRAFT` Plan 可显式转为 `RETIRED` 以放弃创建，不物理删除。

| 起始状态 | 目标状态 |
|---|---|
| `DRAFT` | `ACTIVE`、`RETIRED` |
| `ACTIVE` | `RETIRED` |
| `RETIRED` | `ACTIVE` |

未列出的 Plan 状态迁移一律拒绝，包括回到 `DRAFT`。

Plan 不存在时，服务返回 `404 Not Found` 和 `PLAN_NOT_FOUND`。

请求不在 Plan 状态迁移矩阵中的变更时，服务返回 `409 Conflict` 和 `PLAN_INVALID_STATE_TRANSITION`。

从 `DRAFT` 或 `RETIRED` Plan 创建 Subscription 时，服务返回 `409 Conflict` 和 `PLAN_NOT_ACTIVE`。

创建 Plan 时全局唯一的 `code` 已存在，服务返回 `409 Conflict` 和 `PLAN_CODE_ALREADY_EXISTS`。

## Feature

禁用 Feature 是即时全局权益开关：即使当前 Subscription 快照包含该 Feature，运行时仍必须拒绝访问并返回 `FEATURE_DISABLED`。Plan 配置更新只影响后续 Subscription，不改变此全局禁用语义。

| 状态 | 语义 |
|---|---|
| `DRAFT` | 可编辑，不能加入 Plan、不能授予。 |
| `ACTIVE` | 可编辑，可加入 Plan、可授予。 |
| `DISABLED` | 保留定义与关系，但全局拒绝运行时访问，不能加入新 Plan。 |

| 起始状态 | 目标状态 |
|---|---|
| `DRAFT` | `ACTIVE`、`DISABLED` |
| `ACTIVE` | `DISABLED` |
| `DISABLED` | `ACTIVE` |

未列出的 Feature 状态迁移一律拒绝，包括回到 `DRAFT`。

### 运行时校验顺序与拒绝

| 顺序 | 条件 | HTTP 状态与 `code` |
|---:|---|---|
| 1 | Feature 定义不存在 | `404 Not Found` / `FEATURE_NOT_FOUND` |
| 2 | Feature 为 `DISABLED` | `403 Forbidden` / `FEATURE_DISABLED` |
| 3 | 无 Subscription | `403 Forbidden` / `SUBSCRIPTION_REQUIRED` |
| 3 | 当前 Subscription 为 `PAUSED` | `403 Forbidden` / `SUBSCRIPTION_PAUSED` |
| 3 | 当前 Subscription 为 `CANCELED` | `403 Forbidden` / `SUBSCRIPTION_CANCELED` |
| 3 | 当前 Subscription 已到 `endsAt` | `403 Forbidden` / `SUBSCRIPTION_EXPIRED` |
| 4 | 有效权益快照未授予 Feature | `403 Forbidden` / `FEATURE_NOT_ENTITLED` |

本表在 Tenant 可访问性校验通过后适用。必须按表中顺序判定：`FEATURE_DISABLED` 优先于 Subscription 状态；有效 Subscription 但未授予 Feature 不得伪装为 Subscription 不可用。

请求不在 Feature 状态迁移矩阵中的变更时，服务返回 `409 Conflict` 和 `FEATURE_INVALID_STATE_TRANSITION`。

将 `DRAFT` 或 `DISABLED` Feature 加入 Plan 时，服务返回 `409 Conflict` 和 `FEATURE_NOT_ACTIVE`。

创建 Feature 时全局唯一的 `code` 已存在，服务返回 `409 Conflict` 和 `FEATURE_CODE_ALREADY_EXISTS`。

## Quota

Quota 由三个不同概念构成：`Quota Definition` 是可配置的额度类型，唯一具有配置状态机；`Quota Usage` 是 Tenant 对某 Definition 的当前已用量和额度判定真相；`Quota Operation` 是一次带 `operationId` 的 `consume` 或 `release` 计量记录，用于重试幂等。三者不得合并为单一“Quota 状态”。

MVP 仅支持离散计数型 `max_users` 与 `max_projects` 两个 Quota Definition；存储量、API 调用量、Token 等其他计量类型不进入运行时闭环。

`max_users` 以 Tenant 内启用的 Membership 数量计量。同一 Identity 在不同 Tenant 的 Membership 分别计量；未激活邀请不占用，邀请激活时 `consume(1)`，成员禁用时 `release(1)`。

`max_projects` 以 Tenant 中未删除的 Project 数量计量。Project 创建成功时 `consume(1)`，删除（含软删除）时 `release(1)`；恢复已删除 Project 必须再次 `consume(1)`。

Quota Definition 不提供全局绕过额度的 `DISABLED` 语义。下架只阻止它加入未来 Plan 和 Subscription 快照；已有快照仍继续强制执行额度。

| 状态 | 语义 |
|---|---|
| `DRAFT` | 可编辑，不能加入 Plan、不能生成 Subscription 快照。 |
| `ACTIVE` | 可编辑，可加入 Plan、可生成新快照。 |
| `RETIRED` | 保留查询和已有快照的执行，不能加入新的 Plan 或快照。 |

| 起始状态 | 目标状态 |
|---|---|
| `DRAFT` | `ACTIVE`、`RETIRED` |
| `ACTIVE` | `RETIRED` |
| `RETIRED` | `ACTIVE` |

未列出的 Quota Definition 状态迁移一律拒绝。重新启用只影响未来 Plan 和 Subscription 快照，不回溯改变已有快照。

`check` 是无副作用、无预留的即时查询，返回当前 `limit`、`used`、`remaining` 和 `allowed`。它不保证随后的 `consume` 仍成功；只有 `consume` 可以原子占用额度。

MVP 的 `consume` 与 `release` 固定调整一个单位，不接受调用方传入任意数量。

PostgreSQL 是额度唯一真相。`consume` 对 `(tenantId, quotaDefinitionId)` 执行单条条件更新，只有 `used < limit` 时才将 `used` 加一；Redis 不保存或裁决额度真相。

`consume` 因额度已满失败时，服务返回 `409 Conflict` 和 `QUOTA_EXCEEDED`，并在 Problem Details 中提供当前 `limit`、`used`、`remaining = 0`。

`release` 对同一 `(tenantId, quotaDefinitionId)` 仅在 `used > 0` 时原子减一。若已为零，服务返回 `409 Conflict` 和 `QUOTA_RELEASE_UNDERFLOW`，不静默成功。

`consume` 与 `release` 必须携带调用方稳定生成的 `operationId`，`check` 不需要。`operationId` 绑定业务资源的计量动作，独立于保护外部 HTTP 请求的 `Idempotency-Key`。

`operationId` 必须为全局唯一 UUIDv7；`quota_operations` 对它建立全局唯一约束，不按 Tenant 或 Quota Definition 分区。

动作、Tenant 与 Quota Definition 均相同的重复 `operationId` 必须原样重放首次结果，不重复调整用量；任一项不同则返回 `409 Conflict` 和 `QUOTA_OPERATION_ID_REUSED`，且不调整用量。

管理员初始化使用的内部 Quota Command 必须额外携带 `purpose = TENANT_ADMIN_INITIALIZATION`，并将 purpose 纳入 Quota Operation 的不可变幂等指纹。只有 `tenant-access-service` 的 Service Access Token、`entitlement:quota:write`、`quotaCode = max_users` 与 `amount = 1` 同时满足时，Entitlement 才允许在 Tenant 仍为 `PENDING` 时执行该 provisioning `consume`；对应 `release` 必须保持相同 purpose。普通 `runtime:quota:write` 不能使用该例外，仍须先满足 Tenant 可访问性。Entitlement 不为此回调 Tenant Access，避免形成 Tenant Access → Entitlement → Tenant Access 的同步环。

`quota_operations` 是不可变计量审计记录，不采用 HTTP 幂等记录的 24 小时过期。其保留期遵循平台审计保留策略；保留期间 `operationId` 不可重新使用。

携带有效 `operationId` 的 `consume` 或 `release` 即使得到业务 `4xx`，也必须写入 Quota Operation。相同动作、Tenant 与 Quota Definition 的后续重试原样重放首次结果，即使额度、权益或 Tenant 状态已经变化；不得把原先的失败改为成功。

`consume` 或 `release` 缺失或空白 `operationId` 时，服务返回 `400 Bad Request` 和 `QUOTA_OPERATION_ID_REQUIRED`；非 UUIDv7 时返回 `400 Bad Request` 和 `QUOTA_OPERATION_ID_INVALID`。两种情形均不调整用量、不写入 Quota Operation。

`check` 成功时返回 `200 OK` 和当前 `limit`、`used`、`remaining`、`allowed`。成功的 `consume` 与 `release` 返回 `200 OK`、调整后的 `limit`、`used`、`remaining` 以及 `operationId`。

MVP 的 Plan Quota Limit 必须为非负整数。Quota Definition 缺失表示未授予，`0` 表示授予但不可使用；不支持 `null`、特殊值或无限额度语义。

套餐变更后若新 Limit 低于当前 `used`，仍允许替换 Subscription，不删除既有资源。此时 `remaining = 0`、`allowed = false`，后续 `consume` 返回 `QUOTA_EXCEEDED`，直到通过资源释放将用量降回上限内。

`release` 不依赖当前 Subscription 是否仍授予该 Quota，只要 Quota Definition 仍存在即可执行；平台或内部工作流的 `release` 清理也不受 Tenant 可访问性限制。`check` 与 `consume` 必须按当前权益判断；普通 Tenant 用户请求仍须先通过 Tenant 访问校验。

`check` 与 `consume` 在 Tenant 可访问性校验通过后，必须依次判定：Quota Definition 存在、Subscription 可用、当前权益快照授予该 Quota、额度是否足够。Definition 不存在时返回 `404 Not Found` / `QUOTA_DEFINITION_NOT_FOUND`；无 Subscription、Subscription `PAUSED`、`CANCELED`、到期时依次返回 `403 Forbidden` / `SUBSCRIPTION_REQUIRED`、`SUBSCRIPTION_PAUSED`、`SUBSCRIPTION_CANCELED`、`SUBSCRIPTION_EXPIRED`；快照未授予时返回 `403 Forbidden` / `QUOTA_NOT_ENTITLED`。额度不足时 `check` 返回 `200 OK` 和 `allowed = false`，`consume` 返回 `409 Conflict` / `QUOTA_EXCEEDED`。

请求不在 Quota Definition 状态迁移矩阵中的变更时，服务返回 `409 Conflict` 和 `QUOTA_DEFINITION_INVALID_STATE_TRANSITION`。

将 `DRAFT` 或 `RETIRED` Quota Definition 加入 Plan 时，服务返回 `409 Conflict` 和 `QUOTA_DEFINITION_NOT_ACTIVE`。

创建 Quota Definition 时全局唯一的 `code` 已存在，服务返回 `409 Conflict` 和 `QUOTA_DEFINITION_CODE_ALREADY_EXISTS`。

## Invitation

Invitation 到期由 `expiresAt` 在激活时派生；`EXPIRED` 不属于其持久状态。实现不得依赖定时任务先改写状态；到期的激活请求必须即时拒绝。

| 状态 | 语义 |
|---|---|
| `PENDING` | 未到 `expiresAt` 时可激活。 |
| `ACCEPTED` | 已成功激活的终态。 |
| `REVOKED` | 管理员撤销的终态。 |

| 起始状态 | 目标状态 |
|---|---|
| `PENDING` | `ACCEPTED`、`REVOKED` |
| `ACCEPTED` | 无 |
| `REVOKED` | 无 |

未列出的 Invitation 状态迁移一律拒绝。`PENDING → REVOKED` 可在到期前后执行，`ACCEPTED` 与 `REVOKED` 都不可复活。

请求不在 Invitation 状态迁移矩阵中的变更时，服务返回 `409 Conflict` / `INVITATION_INVALID_STATE_TRANSITION`。

Invitation 仅在密码凭据已建立、启用 Membership 已创建且 `max_users` `consume(1)` 已成功后，才允许 `PENDING → ACCEPTED`。三者任一失败时 Invitation 保持 `PENDING`。

管理员按 Invitation ID 查询、撤销等操作的目标不存在时，服务返回 `404 Not Found` / `INVITATION_NOT_FOUND`。激活令牌无效或无法解析时仍返回 `400 Bad Request` / `INVITATION_TOKEN_INVALID`，不得借此泄露 Invitation 是否存在。

Tenant Access 拥有 Invitation 状态迁移，并在激活时同步编排 IAM 的 Identity/凭据确认与 Entitlement 的 `max_users` `consume`。IAM 遇到已有可用凭据的 Identity 必须复用该 Identity，不得因 Invitation 修改既有密码。若扣减后的步骤失败，Tenant Access 必须持久化 `release` 补偿并保持 Invitation 为 `PENDING`。

一次激活尝试预先分配一对稳定 UUIDv7：一个用于 `max_users` `consume`，另一个用于必要时的 `release` 补偿。同一未完成尝试的重试必须复用这对 `operationId`；若该尝试的补偿已成功释放额度，后续重新激活必须创建新尝试和新的 `operationId`，以重新原子占用额度。激活尝试是工作流记录，不增加 Invitation 的持久状态。

激活顺序固定为：验证令牌并解析 Invitation → 判定所属 Tenant 可访问 → 锁定并验证 `PENDING` Invitation → 使用本次激活尝试的 `consume operationId` 执行 `max_users` `consume` → IAM 确认 Identity 并在其尚无凭据时建立凭据 → Tenant Access 本地事务创建启用 Membership 并写入 `ACCEPTED`。扣减后的后续失败使用本次尝试的 `release operationId` 执行补偿；不得删除或重置已由 IAM 建立的 Identity/凭据。

若 `release` 补偿尚未完成，Invitation 继续保持 `PENDING`，但不得接受新的激活尝试；服务返回 `503 Service Unavailable` / `INVITATION_ACTIVATION_COMPENSATING`，并携带 `Retry-After`。补偿完成后，Invitation 恢复可激活状态。

激活必须依次判定：令牌有效、所属 Tenant 可访问、Invitation 未撤销、未到 `expiresAt`、尚未 `ACCEPTED`、`max_users` 有余量。令牌无效或无法解析时返回 `400 Bad Request` / `INVITATION_TOKEN_INVALID`；Tenant 不可访问时按 Tenant 访问错误返回对应 `TENANT_*`；已撤销时返回 `410 Gone` / `INVITATION_REVOKED`；已到期时返回 `410 Gone` / `INVITATION_EXPIRED`；已接受时返回 `409 Conflict` / `INVITATION_ALREADY_ACCEPTED`；额度满时返回 `409 Conflict` / `QUOTA_EXCEEDED`。

平台创建 Invitation 时固定设置 7 天后的 `expiresAt`。租户管理员不可原地延长有效期；需要延长时必须撤销旧 Invitation 并新建一条。

创建 Invitation 时，邮箱缺失、格式不合法或无法规范化，服务返回 `400 Bad Request` / `INVITATION_EMAIL_INVALID`；不得创建 Invitation，也不得占用幂等键。

同一 Tenant、同一规范化邮箱至多存在一条未到期的 `PENDING` Invitation。再次创建时返回 `409 Conflict` / `INVITATION_ALREADY_PENDING`；已到期的 `PENDING` Invitation 不阻止新建。

若规范化邮箱已关联本 Tenant 的任一 Membership，创建 Invitation 返回 `409 Conflict` / `INVITATION_MEMBERSHIP_ALREADY_EXISTS`。重新启用成员必须走 Membership 管理流程，不得通过新 Invitation 创建第二条成员关系或再次触发 `max_users` 计量。

## Tenant

### 持久状态与访问结果

| 名称 | 类型 | 含义 |
|---|---|---|
| `PENDING` | 持久状态 | 初始 Tenant 管理员尚未初始化完成；拒绝登录、租户切换和业务访问。 |
| `ACTIVE` | 持久状态 | 已完成初始化。仅在 `expiresAt` 为空或尚未到达时允许访问。 |
| `SUSPENDED` | 持久状态 | 平台施加的可恢复人工冻结；拒绝访问。 |
| `CLOSED` | 持久状态 | 不可逆终止；永久拒绝访问。 |
| `EXPIRED` | 派生访问结果 | `status = ACTIVE` 且已到达 `expiresAt` 时的访问结果；不写入 Tenant 状态。 |

`expiresAt` 为可空有效期。到期由访问时刻派生，不依赖定时任务将 Tenant 改写为 `EXPIRED`。

Tenant `expiresAt` 是平台级绝对访问截止时间，不表示从激活时开始计算的相对有效时长，也不替代 Subscription `endsAt`。创建 Tenant 时，非空 `expiresAt` 必须严格晚于服务端当前时间；管理员初始化必须在调用 IAM 或扣减 Quota 前再次检查，若已经达到该时间则返回 `409 Conflict` / `TENANT_EXPIRY_REACHED`，Tenant 保持 `PENDING` 且不产生 Identity、Membership 或 Quota 副作用。`TENANT_EXPIRED` 仍只表示 `ACTIVE` Tenant 的派生访问结果。

### 唯一允许的状态迁移

| 起始状态 | 目标状态 | 条件与语义 |
|---|---|---|
| `PENDING` | `ACTIVE` | 初始 Tenant 管理员初始化成功。初始化失败保持 `PENDING`，以便安全重试。 |
| `PENDING` | `CLOSED` | 平台显式放弃创建；保留记录和审计，不物理删除。 |
| `ACTIVE` | `SUSPENDED` | 平台显式执行可恢复人工冻结。 |
| `SUSPENDED` | `ACTIVE` | 平台显式恢复；仍按 `expiresAt` 判断访问结果。 |
| `ACTIVE` | `CLOSED` | 平台显式、高风险、可审计的终止操作。 |
| `SUSPENDED` | `CLOSED` | 平台显式、高风险、可审计的终止操作。 |

未列出的迁移一律拒绝：`PENDING` 不可转为 `SUSPENDED`，任何状态不可回到 `PENDING`，`CLOSED` 不可迁出。到期、冻结和关闭是不同概念；到期或冻结不会隐式触发关闭。

### 访问拒绝错误

| 场景 | HTTP 状态 | `code` |
|---|---:|---|
| `PENDING` Tenant 请求访问 | `403 Forbidden` | `TENANT_PENDING` |
| `SUSPENDED` Tenant 请求访问 | `403 Forbidden` | `TENANT_SUSPENDED` |
| 已到 `expiresAt` 的 `ACTIVE` Tenant 请求访问 | `403 Forbidden` | `TENANT_EXPIRED` |
| `CLOSED` Tenant 请求访问 | `403 Forbidden` | `TENANT_CLOSED` |

Tenant 不存在时，服务返回 `404 Not Found` 和 `TENANT_NOT_FOUND`。

请求不在 Tenant 状态迁移矩阵中的变更时，服务返回 `409 Conflict` 和 `TENANT_INVALID_STATE_TRANSITION`。

`PENDING → ACTIVE` 的管理员初始化前置条件尚未满足时，服务返回 `409 Conflict` 和 `TENANT_ADMIN_INITIALIZATION_REQUIRED`。

Tenant Administrator Initialization 由 Tenant Access 串行化。它必须先由 IAM 确认初始管理员 Identity，再以稳定 `operationId` 占用一个 `max_users` 名额；随后在 Tenant Access 的同一事务中建立启用 Membership、创建或确保 Tenant Administrator Role 并完成其分配、将 Tenant 转为 `ACTIVE`、写入稳定 HTTP 结果和 Outbox。任何本地提交前的后续失败都必须以稳定 `release operationId` 补偿已占用的额度，Tenant 保持 `PENDING`；Identity 不回滚。若 Identity 尚无可用密码凭据，Tenant Access 在提交后通过持久化工作项请求 IAM 发送一次性、限时的密码设置链接，该投递不回滚激活。

Quota 已扣减而 Tenant Access 本地激活事务未提交时，根工作流进入 `COMPENSATING` 并持续使用原 `releaseOperationId`；补偿未完成期间，同一外部 Key 返回 `503 Service Unavailable` / `TENANT_ADMIN_INITIALIZATION_COMPENSATING` 与 `Retry-After`。补偿成功后，原根工作流稳定结束为 `409 Conflict` / `TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED`；客户端必须使用新的 `Idempotency-Key` 创建新根工作流及新的 Identity、consume、release 子操作 ID。Identity 保留并复用，Tenant 保持 `PENDING`；不得复用已被 release 抵消的 `consumeOperationId`，也不得在同一根工作流内无限产生新尝试。Password Setup 投递发生在本地激活提交后，失败只重试投递工作项，不触发 Quota 补偿。

每个 Tenant 恰有一个系统管理的 Tenant Administrator Role，固定 `roleKey = TENANT_ADMINISTRATOR` 且 `systemManaged = true`，不得重命名、删除或转为自定义角色。初始化事务必须幂等创建该角色并建立唯一 Membership–Role Assignment；管理员身份只由该 Assignment 表达，不增加 `is_admin` 字段或绕过 RBAC。当前切片不提前创建 Permission 或 Role–Permission 数据；第 4 阶段只能为该既有角色绑定冻结的 `system:*` Permission，不得创建第二个管理员角色。

Initial Tenant Administrator 是独立、不可变的 Tenant–Membership 关系，每个 Tenant 最多一条且 Membership 必须属于该 Tenant。它必须与 Membership、Tenant Administrator Role Assignment 和 `PENDING → ACTIVE` 在同一事务写入；后续授予或撤销管理员角色不改变该历史关系。Password Setup 重发只能通过它定位 Identity，不得在 Membership 冗余邮箱、密码状态或 `is_initial_admin`。已 `ACTIVE` 的 Tenant 使用新幂等键再次初始化时返回 `409 Conflict` / `TENANT_ALREADY_INITIALIZED`，不能替换 Initial Tenant Administrator。

Password Setup Challenge 只允许从未拥有任何 Credential 的 Identity 首次建立密码凭据。IAM 使用 CSPRNG 生成 256 位 Token 并仅保存 SHA-256 摘要；Challenge 自创建起有效 24 小时，每个 Identity 同时最多一个有效 Challenge，重发原子作废旧 Challenge。成功兑换必须携带 UUIDv7 `Idempotency-Key`，并在同一事务中创建 Password Credential、消费 Challenge 与记录稳定 `204` 结果；相同 Token 与相同 Key 可重放成功，其他 Key 不得复用已消费 Token。已有有效 Password Credential 的 Identity 直接复用；存在有效或过期 Initial Platform Credential，或存在已失效 Password Credential 时，管理员初始化在 Quota 扣减前返回 `409 Conflict` / `IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。因先前失败工作流创建但始终没有 Credential 的 Identity 仍允许 Setup。无效、过期、已使用或已被替换的 Token 统一返回 `400 Bad Request` / `PASSWORD_SETUP_TOKEN_INVALID`；不得借此重置或替换密码，幂等指纹也不得保存或快速哈希新密码。
