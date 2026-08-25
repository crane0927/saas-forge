# SaaS Forge Core Domain

saas-forge 为单产品、多 Tenant SaaS 提供访问控制、权益和审计等业务无关的核心能力。

## Platform Access Control

**Identity**:
IAM 管理的全局认证主体，以唯一的规范化邮箱地址识别，并可有显示名；它独立于 Tenant，也不等同于任何 Membership。
_Avoid_: User, account, tenant user

**Default Platform Admin**:
首次部署时由 IAM 显式部署引导任务创建的全局 `Identity`，绑定 Platform 角色而非任何 Tenant `Membership`。其初始密码仅由外部密钥管理系统注入，IAM 只保存 Argon2id 哈希，并要求首次登录时修改。
_Avoid_: Default tenant administrator, superuser, tenant administrator

**Initial Platform Credential**:
仅用于首次设置 Default Platform Admin 密码的随机初始密码，在创建后 24 小时失效且不能执行 Platform 管理操作。成功改密后该凭据永久失效并保留失效记录；失效或疑似泄露时只能通过受限、可审计的部署侧重置替换。
_Avoid_: Platform access credential, default password

**Initial Credential Session**:
Initial Platform Credential 验证成功后建立的受限浏览器会话，只证明该 Identity 可以完成首次改密，不建立 Tenant Context，也不是 User Access Token 会话。
_Avoid_: Platform admin session, user access token

**Credential**:
由 IAM 管理、供一个 Identity 证明其认证能力的凭据。MVP 仅有 `INITIAL_PLATFORM_PASSWORD` 与 `PASSWORD` 两种类型。
_Avoid_: API key, external identity

**Password Credential**:
一个 Identity 用于常规密码认证的 `PASSWORD` Credential；首次改密时新建该记录，原 Initial Platform Credential 永久失效。一个 Identity 任意时刻最多有一个有效 Password Credential，IAM 仅持久化其 Argon2id 哈希，绝不保存原始密码。
_Avoid_: Password, shared secret

**Password Setup Challenge**:
IAM 为从未拥有任何 Credential 的 Identity 签发的一次性、限时密码建立凭据；它只能用于首次建立 Password Credential，不能用于重置、替换或绕过既有凭据的恢复流程。
_Avoid_: Password reset token, invitation token, initial password

**Password Recovery**:
已有 Password Credential 的 Identity 在无法继续使用当前凭据时，通过独立验证流程替换该凭据的恢复过程。它不属于 Invitation 激活或首次 Password Setup。
_Avoid_: Password Setup, Invitation activation, initial password

**User Access Token**:
IAM 为已认证 Identity 签发的短期用户访问凭证；它可以表示 Platform 全局身份，或成对绑定一个已验证的 Membership 与 Tenant 上下文。
_Avoid_: Refresh Token, service token, role token

**Access Token Issuance**:
IAM 对一个已签发 User Access Token 保存的非敏感生命周期索引，使安全事件可以定位并撤销尚未到期的 `jti`；它不是 Token 本身，也不参与常规请求验签。
_Avoid_: Access token store, session token

**Revocation Index**:
IAM 将尚未到期的 Access Token 与 Signing Key 撤销事实投影到 Redis 后形成的即时拒绝索引；只有索引已与持久化事实完成同步时，验证方才可依赖它作出放行决定。
_Avoid_: Authorization database, token introspection

**Revocation Fence**:
IAM 为一个 Membership 或 Tenant 建立的安全边界，使目标范围在批量会话撤销期间不能签发或使用新的 User Access Token。它不是 Tenant 或 Membership 的领域状态，也不替代对既有 `jti` 的撤销。
_Avoid_: Tenant Suspension, Membership status, jti blacklist

**Login Context Intent**:
浏览器登录时对 Platform 或 Tenant 工作上下文的显式选择；它只决定 IAM 校验哪类权威访问关系，不携带或证明任何 Role、Membership 或 Tenant 身份。
_Avoid_: Tenant selection, inferred login origin

**Platform Role**:
在一个 Platform 全局范围内授予权限的角色，与只在单个 Tenant 内生效的 Tenant Role 相互独立。
_Avoid_: Tenant role, membership role

**JWT Signing Key**:
用于以 `RS256` 签发 Access Token 的非对称签名密钥。每个密钥版本以唯一 `kid` 标识；生产环境的私钥不可导出，由 KMS/HSM 保管，IAM 仅可用工作负载身份请求签名。
_Avoid_: JWT secret, shared signing secret

**Signing Key Metadata**:
IAM 对一个 JWT Signing Key 版本保存的公开标识、KMS/HSM Key Version 引用、JWKS 公开材料与生命周期记录，不包含私钥。其生命周期为 `PUBLISHED`、`ACTIVE`、`RETIRING`、`RETIRED`，或不可逆的 `REVOKED`。
_Avoid_: Private key, JWT secret

**Revoked Signing Key**:
已被判定疑似泄露、不得再用于签发或验证 Access Token 的 JWT Signing Key 版本。验证方必须拒绝其 `kid`，即使仍持有对应公钥的缓存。
_Avoid_: Retired signing key, expired signing key

**Refresh Token Family**:
为同一浏览器会话连续签发并轮换的 Refresh Token 谱系，持有该会话的 Identity、Purpose 及相应的 Platform、Membership/Tenant、待选择或首次改密上下文。它自首次登录起最长有效 8 小时、空闲最长 30 分钟，轮换不延长期限；已轮换 Token 的摘要保留至 Family 到期，任一不属于短时恢复语义的重放都会撤销整个 Family。
_Avoid_: Access token, browser cookie

**Refresh Token Family Context Version**:
Refresh Token Family 上 Purpose、Membership 与 Tenant 上下文变化的单调版本；普通活动与 Token 轮换不改变它，读取旧版本准备的 Token 结果不能提交到已经变化的 Family。
_Avoid_: Last used time, token version, refresh count

**Refresh Rotation Lease**:
IAM 为同一旧 Refresh Token 的一次在途轮换建立的短期并发边界，用于拒绝重复执行而不把重叠请求立即判为重放攻击；它不是会话状态，也不延长 Token 或 Family 生命周期。
_Avoid_: Refresh token lock, session lease

**OAuth Client**:
由 IAM 注册、以 `client_id` 和显式 scope 表示的服务认证主体。它不代表用户、Membership 或 Tenant Context。
_Avoid_: User, service tenant identity

**Service Access Token**:
IAM 通过 Client Credentials 为 OAuth Client 签发的短期访问凭证，只表示 `client_id` 与显式 scope，不建立 Identity、Membership 或 Tenant Context。
_Avoid_: User Access Token, tenant token, service tenant identity

**Client Secret**:
OAuth Client 的机器生成认证机密，仅在创建或轮换时明文展示一次。IAM 仅保存其 SHA-256 摘要；轮换后的旧 Secret 最多与新 Secret 重叠 24 小时，重叠期内不允许再次轮换。
_Avoid_: Password, API key

## Browser Delivery

**Controlled Browser Origin**:
位于同一完全受控可注册根域下、以 HTTPS 提供 Platform Console、Tenant Console Shell、API Gateway 或业务 Remote 的固定浏览器 Origin。未登记的 Origin 不属于平台浏览器交付边界。
_Avoid_: Arbitrary remote origin, customer-provided origin

**Refresh Token Cookie**:
仅由 API Gateway 的 `api.<root>` Origin 签发和接收的 host-only Cookie，用于携带 Refresh Token。它不被 Platform Console、Tenant Console Shell 或业务 Remote 读取。
_Avoid_: Shared domain cookie, browser token store

**CSRF-Protected Browser Request**:
来自 Platform Console 或 Tenant Console Shell、可能改变平台状态的浏览器请求。它通过精确 Origin、Fetch Metadata 和专用请求头证明来自受控 Console，而非跨站表单或脚本。
_Avoid_: Remote API request, cross-site browser request

**Browser Origin Allowlist**:
由部署期 `browser.rootDomain` 推导的、可向 API Gateway 发起凭据型浏览器请求的固定 Origin 集合。它仅包含 Platform Console 和 Tenant Console Shell，不能被 Manifest 或运行时注册扩展。
_Avoid_: Dynamic CORS allowlist, remote API allowlist

**Local Browser Topology**:
用于开发与端到端测试的受控浏览器 Origin 集合，保持与生产相同的主机分离模型。它以 `saasforge.test` 作为本地根域，不代表可用于生产的域名。
_Avoid_: localhost port topology, production root domain

## Tenancy

**Tenant**:
SaaS 客户的逻辑隔离空间，其业务含义由接入产品定义。Tenant 的持久生命周期状态为 `PENDING`、`ACTIVE`、`SUSPENDED` 或 `CLOSED`。
_Avoid_: Customer, account

**Tenant Context**:
面向用户请求、由已验证 Access Token 的 `membershipId` 解析出的可信 Tenant 安全上下文。用户不能通过请求头、查询参数、请求体或其语义等价别名传入或覆盖它；Client Credentials 服务令牌也不建立该上下文。
_Avoid_: Client-selected tenant, service tenant identity

**Tenant Operation Target**:
已认证服务在契约明确的内部调用或可信消息元数据中携带的 Tenant 标识，用于确定业务操作目标。它不是 Tenant Context，不代表用户、Membership 或 Tenant 身份；下游必须按 `client_id` 与显式 `scope` 授权并校验目标 Tenant 与业务资源的关系。
_Avoid_: Service tenant context, impersonated tenant

**Tenant Access Status**:
Tenant 在特定时刻是否可访问平台的派生结果；当 Tenant 为 `ACTIVE` 且未到 `expiresAt` 时可访问，达到 `expiresAt` 时结果为 `EXPIRED`。
_Avoid_: Tenant status, expired Tenant state

**Tenant Administrator Initialization**:
为新 Tenant 建立初始管理员 Membership 并授予 Tenant Administrator Role 的过程。只有该过程成功后，`PENDING` Tenant 才可变为 `ACTIVE`。
_Avoid_: Early activation

**Initial Tenant Administrator**:
Tenant 首次激活时确定的初始管理员 Membership，是不会随后续角色授予或撤销而改变的历史关系；它用于定位首次 Password Setup 的恢复目标，不表示永久拥有管理员权限。
_Avoid_: Current tenant administrator, administrator flag, tenant owner

**Tenant Administrator Role**:
某一 Tenant 专属、由系统管理的 Tenant Role，授予该 Tenant 的核心 `system` 管理权限。它不授予任何业务模块 Permission，也不是绕过 RBAC 的管理员标记。
_Avoid_: Superuser, administrator flag

**Tenant Suspension**:
平台对可恢复 Tenant 执行的人工访问冻结。`SUSPENDED` Tenant 可在显式恢复后回到 `ACTIVE`，但仍受有效期约束。
_Avoid_: Expiration, closure

**Tenant Closure**:
平台对 Tenant 执行的不可逆终止。处于 `CLOSED` 的 Tenant 永久拒绝访问，不能通过状态回迁恢复；正常运行或已冻结 Tenant 的关闭是显式、高风险且可审计的操作。
_Avoid_: Suspension, reactivation

## Entitlement

**Subscription**:
Tenant 在一个有效期内获得指定 Plan 权益的关系。Subscription 到期由 `endsAt` 在权益判断时派生，而非持久化为 `EXPIRED` 状态。
_Avoid_: Contract, billing account

**Trial Eligibility**:
Tenant 获得一次试用 Subscription 的资格。套餐变更不会重置已开始的试用期限。
_Avoid_: Repeated trial, trial reset

**Subscription Version**:
一次 Subscription 生命周期的不可变权益记录。套餐变更产生新版本并将旧版本标记为 `SUPERSEDED`。
_Avoid_: In-place plan update

**Plan**:
产品向 Tenant 提供的 Feature 与 Quota 组合。Plan 配置是新 Subscription 的快照来源，不回溯改变既有 Subscription 权益。
_Avoid_: Subscription, application plan

**Feature**:
可由 Plan 授予的产品能力。禁用的 Feature 是全局运行时开关，会覆盖 Subscription 快照中的同名权益。
_Avoid_: Permission, feature flag

**Quota Definition**:
可由 Plan 配置的额度类型定义。它不同于 Tenant 已用量和单次计量操作。
_Avoid_: Usage, quota counter

**Quota Usage**:
Tenant 对一个 Quota Definition 的当前已用量，是额度判定的权威累计值。
_Avoid_: Quota definition, operation log

**Quota Operation**:
一次带 `operationId` 的额度扣减或释放请求，用于保证计量重试幂等。
_Avoid_: Usage, quota definition

## Tenant Access

**Membership**:
一个 Identity 加入某一 Tenant 的成员关系；它决定该 Identity 能否在该 Tenant 建立 Tenant Context，并可被启用或禁用。
_Avoid_: Tenant account, user-tenant mapping

**Accessible Membership**:
在当前时刻处于启用状态、且所属 Tenant 可访问的 Membership；只有它可以成为 User Access Token 的 Tenant 上下文候选。
_Avoid_: Existing membership, active user

**Tenant Context Switch**:
当前浏览器会话从一个 Accessible Membership 转向另一个 Accessible Membership 的变更；它只影响该会话，切换前由该会话签发且仍有效的 User Access Token 均不再可用。
_Avoid_: Account switch, global Tenant switch

**Invitation**:
邀请指定邮箱加入 Tenant 的一次性、限时凭据。Invitation 到期由 `expiresAt` 在激活时派生，而非持久化为 `EXPIRED` 状态。
_Avoid_: Registration, user creation

## Audit

**Audit Record**:
由 Audit 服务根据已提交领域事实只追加保存的合规与业务追责记录；它不裁决来源工作流是否成功。
_Avoid_: Application log, audit event

## Contract Governance

**Committed Fact Event**:
由其数据权威服务提交后对外传播的不可变事实；它不是命令，也不表示某个跨服务工作流已经成功。重投同一事实时保留同一 Event ID 与 Trace 关联。
_Avoid_: Message, command, notification

**v1 Contract Baseline**:
仓库中经显式评审后固定且不可修改的 REST、Protobuf 和事件 v1 已发布契约快照；兼容性门禁以全部历史基线为依据拒绝破坏性变更。
_Avoid_: Current contract, generated client, automatic snapshot
