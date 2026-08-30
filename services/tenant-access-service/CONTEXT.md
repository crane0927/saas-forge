# Tenant Access Context

Tenant Access 管理 Tenant、Membership、Tenant 范围访问关系与生命周期，并拥有跨服务 Tenant 工作流的根状态。

## Language

**Tenant**:
SaaS 客户的逻辑隔离空间，其业务含义由接入产品定义；持久生命周期状态为 `PENDING`、`ACTIVE`、`SUSPENDED` 或 `CLOSED`。
_Avoid_: Customer, account

**Tenant Brand Profile**:
Tenant 的受控品牌表达，仅在建立该 Tenant Context 后生效；未建立 Tenant Context 时使用平台品牌。它不改变平台统一的布局、组件和交互语义。
_Avoid_: Tenant theme, custom CSS, branded layout

**Tenant Context**:
面向用户请求、由已验证 User Access Token 的 `membershipId` 解析出的可信 Tenant 安全上下文；客户端不能传入或覆盖它，Service Access Token 也不建立它。
_Avoid_: Client-selected tenant, service tenant identity

**Tenant Operation Target**:
已认证服务在正式契约中携带的 Tenant 标识，用于确定业务操作目标；它不是 Tenant Context，也不代表用户、Membership 或 Tenant 身份。
_Avoid_: Service tenant context, impersonated tenant

**Tenant Access Status**:
Tenant 在特定时刻是否可访问平台的派生结果；当 Tenant 为 `ACTIVE` 且未到 `expiresAt` 时可访问，达到 `expiresAt` 时结果为 `EXPIRED`。
_Avoid_: Tenant status, expired Tenant state

**Tenant Administrator Initialization**:
为新 Tenant 建立初始管理员 Membership 并授予 Tenant Administrator Role 的过程；只有成功后，`PENDING` Tenant 才可变为 `ACTIVE`。
_Avoid_: Early activation

**Initial Tenant Administrator**:
Tenant 首次激活时确定的初始管理员 Membership，是不会随后续角色授予或撤销而改变的历史关系。
_Avoid_: Current tenant administrator, administrator flag, tenant owner

**Tenant Administrator Role**:
某一 Tenant 专属、由系统管理的 Tenant Role，授予该 Tenant 的核心 `system` 管理权限；它不是超级用户标记。
_Avoid_: Superuser, administrator flag

**Tenant Suspension**:
平台对可恢复 Tenant 执行的人工访问冻结；`SUSPENDED` Tenant 可在显式恢复后回到 `ACTIVE`，但仍受有效期约束。
_Avoid_: Expiration, closure

**Tenant Closure**:
平台对 Tenant 执行的不可逆终止；处于 `CLOSED` 的 Tenant 永久拒绝访问。
_Avoid_: Suspension, reactivation

**Membership**:
一个 Identity 加入某一 Tenant 的成员关系；它决定该 Identity 能否在该 Tenant 建立 Tenant Context，并可被启用或禁用。
_Avoid_: Tenant account, user-tenant mapping

**Accessible Membership**:
在当前时刻处于启用状态、且所属 Tenant 可访问的 Membership；只有它可以成为 User Access Token 的 Tenant 上下文候选。
_Avoid_: Existing membership, active user

**Tenant Context Switch**:
当前浏览器会话从一个 Accessible Membership 转向另一个 Accessible Membership 的变更；它只影响该会话。
_Avoid_: Account switch, global Tenant switch

**Invitation**:
邀请指定邮箱加入 Tenant 的一次性、限时凭据；到期由 `expiresAt` 在激活时派生，而非持久化为 `EXPIRED` 状态。
_Avoid_: Registration, user creation
