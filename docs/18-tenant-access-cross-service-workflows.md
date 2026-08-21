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

同步内部调用除生产传输层 mTLS 外还必须使用 Client Credentials 服务 Token，并由接收方校验 `client_id` 与精确 Scope。IAM 调 Tenant Access 查询或验证 Membership 只使用 `tenant-access:membership:read`；Entitlement 调 Tenant Access 校验首个 Subscription 的 Tenant 资格只使用 `tenant-access:tenant:read`；Tenant Access 调 IAM 确认 Identity、投递 Password Setup 与复核 Platform Role 分别使用 `iam:identity:write`、`iam:password-setup:write`、`iam:platform-role:read`，调 Entitlement 计量只使用 `entitlement:quota:write`。Tenant Operation Target 只表达操作目标，不替代服务身份或 Scope。Tenant Access 与 Entitlement 的平台管理 API 必须向 IAM 复核调用 `identityId` 当前持有要求的 Platform Role，不能根据 Token 缺少 Tenant Claim 推定；IAM 或 Tenant Access 权威校验不可用时 fail-closed。

本流程的内部同步契约固定为版本化 Protobuf：IAM `IdentityProvisioningService.EnsureIdentity(requestId, email, displayName)` 返回 `identityId` 与 `PASSWORD_READY`、`SETUP_ALLOWED` 或 `RECOVERY_REQUIRED`；IAM `PasswordSetupService.DeliverPasswordSetup(requestId, identityId)` 只按 IAM 权威 Identity 读取收件地址，调用方不得传邮箱；IAM `PlatformAuthorizationService.CheckPlatformRole(identityId, roleKey)` 只返回当前是否授权，不暴露全部角色；Tenant Access `TenantProvisioningQueryService.CheckInitialSubscriptionEligibility(tenantId)` 只返回 `PENDING_ELIGIBLE`、`NOT_FOUND`、`INVALID_STATE` 或 `EXPIRY_REACHED`；Entitlement `QuotaCommandService.Consume/Release(tenantId, quotaCode, amount, operationId, purpose)` 返回当前 `usage`、`limit` 与是否为幂等重放。Entitlement 创建首个 Subscription 前必须调用 Tenant Access 资格校验，只允许尚未到绝对 `expiresAt` 的 `PENDING` Tenant；不得缓存或复制 Tenant 状态，也不得用内部 REST 或共享领域实体替代这些边界。

管理员初始化的 Quota Command 固定 `purpose = TENANT_ADMIN_INITIALIZATION`。只有 `tenant-access-service`、`entitlement:quota:write`、`max_users` 与 `amount = 1` 的组合可在 `PENDING` Tenant 上执行 provisioning `consume`；对应补偿 `release` 使用相同 purpose。purpose 必须进入 Quota Operation 幂等指纹和事件，普通 Runtime Quota 调用不能使用该例外。Entitlement 不回调 Tenant Access 校验该命令，避免形成同步调用环。

每个公网状态变更由接收服务以 `(调用方, Idempotency-Key)` 绑定唯一根工作流和请求指纹。根工作流一次性持久化下游子操作 ID；超时、进程重启和调用重试必须恢复它，不能创建新 ID。下游服务按自己的稳定子操作 ID 去重：IAM 使用调用服务与请求 ID，Entitlement 使用既有 `operationId`。根服务在同一事务写入本地领域事实、稳定 HTTP 结果、Outbox 和尚未完成的补偿/重试工作项。外部 Key 不跨服务透传。

根工作流采用同步快路径与持久化后台恢复。HTTP 请求在首次跨服务调用前必须持久化根状态、稳定子操作 ID 与下一步动作；请求超时、服务重启或依赖暂时不可用后，Tenant Access Worker 通过数据库租约按业务串行化键继续同一流程，客户端同 Key 重试只查询、协助推进或重放结果，不是恢复发生的前提。Quota 补偿与已提交激活后的 Password Setup 投递即使客户端不再请求也必须持续执行。重试退避由环境配置，达到某个重试次数不得伪造成功或生成新的子操作 ID。

所有事件经 Transactional Outbox 发布，只说明来源服务已提交的事实；它们不是命令，也不构成任何同步步骤的成功条件。消费者以 CloudEvents `id` 去重。事件、日志与 Trace 只使用允许的 ID、状态、时间、操作者和 `traceId`，不得包含密码、激活令牌、Access/Refresh Token、Client Secret 或邮箱原文。

## Tenant 创建与管理员初始化

Tenant Access 接收平台创建 Tenant 的请求并仅在自己的事务中创建 `PENDING` Tenant、HTTP 幂等结果和 `com.saasforge.tenant.created.v1` Outbox 事件；创建 Tenant 不调用 IAM 或 Entitlement。Subscription 由 Entitlement 的独立平台操作创建，管理员初始化前必须已有有效 Subscription 和足够的 `max_users` 额度。

Tenant `expiresAt` 是创建时确定的绝对平台访问截止时间，不从激活时重新计时，也不替代 Subscription `endsAt`。创建时非空值必须晚于服务端当前时间；管理员初始化必须在调用 IAM 或扣减 Quota 前复核，已经达到时返回 `409 / TENANT_EXPIRY_REACHED`，Tenant 保持 `PENDING` 且不产生跨服务副作用。

当前最小生命周期切片只实现创建 `PENDING` 与管理员初始化完成后的 `PENDING → ACTIVE`。虽然 v1 OpenAPI 已声明 Tenant 冻结与恢复资源，但它们必须与 IAM 会话撤销及 `jti` 黑名单链路同步交付；在该安全前置完成前不得只提交 `SUSPENDED` 或 `ACTIVE` 状态变更。`CLOSED` 也不属于本切片的公开操作。

Tenant Access 平台操作在验证 User Access Token 并经 IAM 复核 Platform Role 后，才能把服务器生成或路径定位的 Tenant ID 作为 Tenant Operation Target，并以事务级 `app.tenant_id` 执行 RLS 受限事务。后台 Worker 只从权威工作流记录恢复该目标；任何请求输入都不能直接设置数据库上下文，运行账号不得拥有 `BYPASSRLS` 或迁移权限。IAM 的 Accessible Membership 查询继续使用按 Identity 限定、字段与数量受限的 `SECURITY DEFINER` 函数，并在进入该查询前验证 `tenant-access:membership:read`。

管理员初始化的根服务为 Tenant Access，且按 `tenantId` 串行化。它持久化 `identityRequestId`、`consumeOperationId` 和 `releaseOperationId` 后按如下顺序执行：

1. 调 IAM 确认规范化邮箱对应的 Identity；已有有效 Password Credential 时复用，完全没有 Credential 记录时允许后续 Password Setup。存在有效或过期 Initial Platform Credential，或存在已失效 Password Credential 时，在 Quota 扣减前返回 `409 / IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。新建 Identity 没有 Membership、没有可用密码凭据时不可登录，后续重试可复用它。
2. 调 Entitlement `consume(max_users, consumeOperationId)`；无有效 Subscription 或额度不足时不创建 Membership，Tenant 保持 `PENDING`。
3. 在 Tenant Access 单一事务内创建启用的初始管理员 Membership、写入每个 Tenant 唯一且不可变的 Initial Tenant Administrator 关系、幂等创建或确保固定 `roleKey = TENANT_ADMINISTRATOR` 且 `systemManaged = true` 的 Tenant Administrator Role、建立唯一 Membership–Role Assignment、将 Tenant 转为 `ACTIVE`，同时写入稳定 HTTP 结果和 `com.saasforge.tenant.administrator-initialized.v1`。当前切片不创建 Permission 或 Role–Permission 数据，也不增加管理员标记字段。已 `ACTIVE` 的 Tenant 使用新幂等键再次初始化时返回 `409 / TENANT_ALREADY_INITIALIZED`，不得替换初始管理员关系。

第 2 步已成功而第 3 步未提交时，Tenant Access 必须进入 `COMPENSATING` 并以同一 `releaseOperationId` 补偿；补偿未完成时，同一外部 Key 返回 `503 / TENANT_ADMIN_INITIALIZATION_COMPENSATING` 与 `Retry-After`。补偿成功后，原根工作流稳定结束为 `409 / TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED`；客户端必须使用新的 `Idempotency-Key` 发起新根工作流，且新的 Identity、consume、release 子操作 ID 不得复用已被补偿的 ID。Identity 及其既有凭据永不被 Tenant 工作流删除或重置，Tenant 保持 `PENDING`。若该 Identity 尚无凭据，Tenant Access 在第 3 步提交时写入凭据注册工作项；其后以同一 `DeliverPasswordSetup requestId` 调用 IAM 创建和发送一次性、限时的密码设置链接。IAM 只在 SMTP 明确接受后记录稳定成功；未完成重试必须作废旧 Challenge 并生成新链接，迟到的旧邮件因此只含无效 Token。Tenant Access 持续退避重试该工作项，不回滚已激活的 Tenant，也不触发 Quota 补偿；IAM 不得持久化可恢复的明文或加密 Token。

初始管理员从未拥有任何 Credential 且密码设置链接已失效或未送达时，由 Tenant Access 拥有 Platform Admin 发起的 `POST /api/v1/platform/tenants/{tenantId}/administrator-password-setups` 受限重发流程。Tenant Access 必须通过不可变 Initial Tenant Administrator 关系定位目标 Membership 和 Identity，再请求 IAM 作废旧 Password Setup Challenge 并投递新链接；若 Identity 已有有效 Password Credential，则幂等完成且不得重置密码，若存在其他历史 Credential 则返回 `IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。该流程必须携带 `Idempotency-Key`，不向 Platform Admin 返回设置令牌、邮箱或投递内容，也不代替普通密码重置。

该重发 API 只有在 SMTP 明确接受最新有效 Challenge 邮件，或 IAM 确认已有有效 Password Credential 时返回 `204`。投递未完成时，Tenant Access 保留根工作流并返回 `503 / PASSWORD_SETUP_DELIVERY_PENDING` 与 `Retry-After`，后台 Worker 持续使用同一 `DeliverPasswordSetup requestId`；成功后相同外部 Key 稳定重放 `204`。当前切片不为此创建 Job 资源或返回 `202`；凭据处于恢复冲突状态时稳定返回 `409 / IDENTITY_CREDENTIAL_RECOVERY_REQUIRED`。

Password Setup Challenge 由 IAM 的 `POST /api/v1/auth/password-setups` 匿名兑换；请求只携带 43 字符无填充 Base64URL Challenge Token 与新密码，不接受 Identity、Membership 或 Tenant 标识，并受受控 Origin 与 `X-SF-CSRF` 防护。邮件链接仅使用 `https://console.<root>/password-setup#token=<token>`，不得将 Token 放入查询参数；控制台读取后立即清除 Fragment 并只在内存保存。成功兑换只建立该 Identity 的首个 Password Credential，不建立登录会话，用户随后必须通过正常登录取得上下文。

## Invitation 激活

Invitation 激活的公网资源属于 Tenant Access：`POST /api/v1/tenant/invitation-activations`。它不信任客户端提供的 Tenant 上下文，而由 Invitation 令牌定位 Invitation 和 Tenant；Gateway 只路由该请求。Tenant Access 是根服务并拥有 Invitation 的锁定、验证和 `PENDING → ACCEPTED` 状态迁移。

一次尝试固定 `consumeOperationId` 和 `releaseOperationId`，按以下顺序执行：验证令牌、Tenant 可访问性和 `PENDING` Invitation → Entitlement `consume(max_users, consumeOperationId)` → IAM 确认 Identity 并仅在其没有凭据时建立凭据 → Tenant Access 本地事务创建启用 Membership、接受 Invitation、写入 HTTP 幂等结果和 `com.saasforge.invitation.accepted.v1`。已有凭据的 Identity 必须复用，Invitation 不得重置其密码。

扣减成功后的任何失败都只补偿 Quota：Tenant Access 以同一 `releaseOperationId` 重试，Invitation 保持 `PENDING`；Identity 和凭据不回滚。补偿未完成时拒绝新激活尝试并按既有 `503 / INVITATION_ACTIVATION_COMPENSATING` 契约返回。补偿完成后，新激活尝试必须生成新的 Quota 操作 ID，以重新占用额度。

## Tenant 切换

Tenant 切换的根服务是 IAM：`POST /api/v1/auth/tenant-switches` 只接受目标 `membershipId`。IAM 以当前已认证 `identityId` 同步调用 Tenant Access 的 [Membership Validation v1](../contracts/protobuf/tenant_access/membership/v1/membership_validation.proto)；Tenant Access 必须确认 Membership 属于该 Identity、仍启用且所属 Tenant 当前可访问，并只返回权威的 `membershipId`、`tenantId`。

IAM 持久化自身的切换尝试后，先将当前 Access Token `jti` 写入黑名单，再在 IAM 本地事务中更新当前会话的活动 Membership 并写入 `204 No Content` 的 HTTP 幂等结果及 `com.saasforge.iam.tenant-context-switched.v1`。Tenant Console Shell 只在收到 `204` 后调用既有刷新接口取得新 Access Token；切换接口不返回或持久化原始 Token。

Tenant Access 校验或黑名单写入失败时，IAM 不更新会话，也不签发新 Token。若黑名单已写入而最终数据库提交失败，旧 Access Token 可提前失效，但会话仍指向旧 Membership；客户端可通过 Refresh Cookie 取得旧上下文的新 Token，并用相同外部 Key 恢复该切换尝试。这种安全优先的提前下线不允许演变为旧 Token 与新会话上下文并存。

## 成员禁用与 Tenant 冻结

成员禁用和 Tenant 冻结的根服务均为 Tenant Access。它们分别按 `membershipId` 或 `tenantId` 串行化，并持久化 `sessionRevocationId`；成员禁用另持久化 `quotaReleaseOperationId`。两条流程都先同步调用 IAM：禁用按 Membership 撤销全部匹配的 Refresh Token，并将仍有效的 Access Token `jti` 写入黑名单；冻结按 Tenant 对所有成员会话执行同样操作。IAM 成功后 Tenant Access 才能提交自己的领域变更。

成员禁用在 Tenant Access 的单一事务中写入 Membership 禁用事实、HTTP 幂等结果、`quotaReleasePending` 工作项和 `com.saasforge.membership.disabled.v1`。提交后立即调 Entitlement `release(max_users, quotaReleaseOperationId)`；失败时 Membership 不恢复，工作项以同一 ID 持续重试。这样最坏情况只是额度暂未释放，而不会出现额度已释放但成员仍启用的窗口。

Tenant 冻结在 Tenant Access 的单一事务中完成 `ACTIVE → SUSPENDED`、HTTP 幂等结果和 `com.saasforge.tenant.suspended.v1`。它不禁用 Membership、不释放 `max_users`，恢复为 `ACTIVE` 也不恢复已撤销会话；用户必须重新登录或重新切换 Tenant。IAM 的成功会话撤销可独立发布 `com.saasforge.iam.sessions-revoked.v1`，即使后续 Tenant Access 本地提交失败也只表示已发生的安全事实。
