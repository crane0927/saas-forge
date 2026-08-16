# SaaS Forge Core Domain

saas-forge 为单产品、多 Tenant SaaS 提供访问控制、权益和审计等业务无关的核心能力。

## Platform Access Control

**Default Platform Admin**:
首次部署时由系统直接创建的全局 `Identity`，绑定 Platform 角色而非任何 Tenant `Membership`。其初始密码仅由外部密钥管理系统注入，IAM 只保存 Argon2id 哈希，并要求首次登录时修改。
_Avoid_: Default tenant administrator, superuser, tenant administrator

**Initial Platform Credential**:
仅用于首次设置 Default Platform Admin 密码的随机初始密码，在创建后 24 小时失效且不能执行 Platform 管理操作。成功改密后该凭据永久失效；失效或疑似泄露时只能通过受限、可审计的部署侧重置替换。
_Avoid_: Platform access credential, default password

**Platform Role**:
在一个 Platform 全局范围内授予权限的角色，与只在单个 Tenant 内生效的 Tenant Role 相互独立。
_Avoid_: Tenant role, membership role

**JWT Signing Key**:
用于以 `RS256` 签发 Access Token 的非对称签名密钥。每个密钥版本以唯一 `kid` 标识；生产环境的私钥不可导出，由 KMS/HSM 保管，IAM 仅可用工作负载身份请求签名。
_Avoid_: JWT secret, shared signing secret

**Revoked Signing Key**:
已被判定疑似泄露、不得再用于签发或验证 Access Token 的 JWT Signing Key 版本。验证方必须拒绝其 `kid`，即使仍持有对应公钥的缓存。
_Avoid_: Retired signing key, expired signing key

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

**Tenant Access Status**:
Tenant 在特定时刻是否可访问平台的派生结果；当 Tenant 为 `ACTIVE` 且未到 `expiresAt` 时可访问，达到 `expiresAt` 时结果为 `EXPIRED`。
_Avoid_: Tenant status, expired Tenant state

**Tenant Administrator Initialization**:
为新 Tenant 建立初始管理员 Membership 的过程。只有该过程成功后，`PENDING` Tenant 才可变为 `ACTIVE`。
_Avoid_: Early activation

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

**Invitation**:
邀请指定邮箱加入 Tenant 的一次性、限时凭据。Invitation 到期由 `expiresAt` 在激活时派生，而非持久化为 `EXPIRED` 状态。
_Avoid_: Registration, user creation
