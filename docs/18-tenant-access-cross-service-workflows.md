# Tenant Access 跨服务工作流契约

本文冻结 Tenant 创建与管理员初始化、Invitation 激活、Tenant 切换、成员禁用与 Tenant 冻结的跨服务责任。它补充而不替代[核心领域契约](17-core-domain-contracts.md)：未在本文列出的状态、错误码和恢复动作不得由实现自行推定。

## 数据所有权与通用规则

| 组件 | 权威数据与责任 |
|---|---|
| Tenant Access | Tenant、Membership、Tenant Role、Invitation，以及其根工作流、补偿/重试工作项与 Outbox。|
| IAM | Identity、密码凭据、会话、Access Token `jti` 黑名单和会话撤销请求去重。|
| Entitlement | Subscription、权益快照、Quota Usage 与带 `operationId` 的 Quota Operation。|
| Audit | 只追加的 Audit Record；它消费事实事件，不裁决流程成功。|
| Gateway | 公网路由与安全边界；不持有领域数据、不编排领域步骤。|

每个公网状态变更由接收服务以 `(调用方, Idempotency-Key)` 绑定唯一根工作流和请求指纹。根工作流一次性持久化下游子操作 ID；超时、进程重启和调用重试必须恢复它，不能创建新 ID。下游服务按自己的稳定子操作 ID 去重：IAM 使用调用服务与请求 ID，Entitlement 使用既有 `operationId`。根服务在同一事务写入本地领域事实、稳定 HTTP 结果、Outbox 和尚未完成的补偿/重试工作项。外部 Key 不跨服务透传。

所有事件经 Transactional Outbox 发布，只说明来源服务已提交的事实；它们不是命令，也不构成任何同步步骤的成功条件。消费者以 CloudEvents `id` 去重。事件、日志与 Trace 只使用允许的 ID、状态、时间、操作者和 `traceId`，不得包含密码、激活令牌、Access/Refresh Token、Client Secret 或邮箱原文。

## Tenant 创建与管理员初始化

Tenant Access 接收平台创建 Tenant 的请求并仅在自己的事务中创建 `PENDING` Tenant、HTTP 幂等结果和 `com.saasforge.tenant.created.v1` Outbox 事件；创建 Tenant 不调用 IAM 或 Entitlement。Subscription 由 Entitlement 的独立平台操作创建，管理员初始化前必须已有有效 Subscription 和足够的 `max_users` 额度。

管理员初始化的根服务为 Tenant Access，且按 `tenantId` 串行化。它持久化 `identityRequestId`、`consumeOperationId` 和 `releaseOperationId` 后按如下顺序执行：

1. 调 IAM 确认规范化邮箱对应的 Identity；新建 Identity 没有 Membership、没有可用密码凭据时不可登录，后续重试可复用它。
2. 调 Entitlement `consume(max_users, consumeOperationId)`；无有效 Subscription 或额度不足时不创建 Membership，Tenant 保持 `PENDING`。
3. 在 Tenant Access 单一事务内创建启用的初始管理员 Membership、创建或确保 Tenant Administrator Role 并完成分配、将 Tenant 转为 `ACTIVE`，同时写入稳定 HTTP 结果和 `com.saasforge.tenant.administrator-initialized.v1`。

第 2 步已成功而第 3 步未提交时，Tenant Access 必须以同一 `releaseOperationId` 补偿；补偿未完成前继续恢复同一根工作流。Identity 及其既有凭据永不被 Tenant 工作流删除或重置。若该 Identity 尚无凭据，Tenant Access 在第 3 步提交时写入凭据注册工作项；其后调用 IAM 创建和发送一次性、限时的密码设置链接。投递失败只重试该工作项，不回滚已激活的 Tenant。

## Invitation 激活

Invitation 激活的公网资源属于 Tenant Access：`POST /api/v1/tenant/invitation-activations`。它不信任客户端提供的 Tenant 上下文，而由 Invitation 令牌定位 Invitation 和 Tenant；Gateway 只路由该请求。Tenant Access 是根服务并拥有 Invitation 的锁定、验证和 `PENDING → ACCEPTED` 状态迁移。

一次尝试固定 `consumeOperationId` 和 `releaseOperationId`，按以下顺序执行：验证令牌、Tenant 可访问性和 `PENDING` Invitation → Entitlement `consume(max_users, consumeOperationId)` → IAM 确认 Identity 并仅在其没有凭据时建立凭据 → Tenant Access 本地事务创建启用 Membership、接受 Invitation、写入 HTTP 幂等结果和 `com.saasforge.invitation.accepted.v1`。已有凭据的 Identity 必须复用，Invitation 不得重置其密码。

扣减成功后的任何失败都只补偿 Quota：Tenant Access 以同一 `releaseOperationId` 重试，Invitation 保持 `PENDING`；Identity 和凭据不回滚。补偿未完成时拒绝新激活尝试并按既有 `503 / INVITATION_ACTIVATION_COMPENSATING` 契约返回。补偿完成后，新激活尝试必须生成新的 Quota 操作 ID，以重新占用额度。

## Tenant 切换

Tenant 切换的根服务是 IAM：`POST /api/v1/auth/tenant-switches` 只接受目标 `membershipId`。IAM 以当前已认证 `identityId` 同步调用 Tenant Access；Tenant Access 必须确认 Membership 属于该 Identity、仍启用且所属 Tenant 当前可访问，并只返回权威的 `membershipId`、`tenantId`。

IAM 持久化自身的切换尝试后，先将当前 Access Token `jti` 写入黑名单，再在 IAM 本地事务中更新当前会话的活动 Membership 并写入 `204 No Content` 的 HTTP 幂等结果及 `com.saasforge.iam.tenant-context-switched.v1`。Tenant Console Shell 只在收到 `204` 后调用既有刷新接口取得新 Access Token；切换接口不返回或持久化原始 Token。

Tenant Access 校验或黑名单写入失败时，IAM 不更新会话，也不签发新 Token。若黑名单已写入而最终数据库提交失败，旧 Access Token 可提前失效，但会话仍指向旧 Membership；客户端可通过 Refresh Cookie 取得旧上下文的新 Token，并用相同外部 Key 恢复该切换尝试。这种安全优先的提前下线不允许演变为旧 Token 与新会话上下文并存。

## 成员禁用与 Tenant 冻结

成员禁用和 Tenant 冻结的根服务均为 Tenant Access。它们分别按 `membershipId` 或 `tenantId` 串行化，并持久化 `sessionRevocationId`；成员禁用另持久化 `quotaReleaseOperationId`。两条流程都先同步调用 IAM：禁用按 Membership 撤销全部匹配的 Refresh Token，并将仍有效的 Access Token `jti` 写入黑名单；冻结按 Tenant 对所有成员会话执行同样操作。IAM 成功后 Tenant Access 才能提交自己的领域变更。

成员禁用在 Tenant Access 的单一事务中写入 Membership 禁用事实、HTTP 幂等结果、`quotaReleasePending` 工作项和 `com.saasforge.membership.disabled.v1`。提交后立即调 Entitlement `release(max_users, quotaReleaseOperationId)`；失败时 Membership 不恢复，工作项以同一 ID 持续重试。这样最坏情况只是额度暂未释放，而不会出现额度已释放但成员仍启用的窗口。

Tenant 冻结在 Tenant Access 的单一事务中完成 `ACTIVE → SUSPENDED`、HTTP 幂等结果和 `com.saasforge.tenant.suspended.v1`。它不禁用 Membership、不释放 `max_users`，恢复为 `ACTIVE` 也不恢复已撤销会话；用户必须重新登录或重新切换 Tenant。IAM 的成功会话撤销可独立发布 `com.saasforge.iam.sessions-revoked.v1`，即使后续 Tenant Access 本地提交失败也只表示已发生的安全事实。
