# saas-forge 安全设计

## 合规与安全基线

首期以中国境内的个人信息保护法、数据安全法、网络安全法和等保定级评估为基线。最终等保等级必须在实施前定级，并按实际定级结论建设；不预设具体等级。

无论最终等级如何，MVP 必须覆盖身份鉴别与访问控制、网络边界与通信安全、主机/应用/数据安全、安全审计与集中监测、备份恢复与灾难恢复、漏洞/变更/应急响应，以及安全制度、责任人和操作记录。数据出境、GDPR、医疗、金融和支付合规仅在实际业务、数据类别和运营地区触发时纳入。

## 身份认证与会话

### 用户令牌

- Access Token 是约 15 分钟有效的 JWT，包含 `identityId`、`membershipId`、`tenantId`、`jti`；Gateway 与 SDK 以 IAM JWKS 本地验签。
- Refresh Token 是随机不透明字符串，浏览器仅以 HttpOnly Cookie 发送；PostgreSQL 只保存其哈希并作为权威记录，Redis 可缓存会话状态。
- 令牌切换 Tenant 必须调用 IAM；IAM 同步校验 Membership 有效后签发绑定新 `membershipId`、`tenantId` 的新令牌。客户端不得覆写 Tenant。
- 普通登出只撤销 Refresh Token。密码重置、成员禁用、Tenant 冻结、强制下线等安全事件将 JWT `jti` 写入 Redis 黑名单，TTL 为 Token 剩余有效期。
- Gateway 与 SDK 每次用户请求检查黑名单；Redis 不可用时 fail-closed，避免已失效 Token 被继续接受。

浏览器 Cookie 必须设置 `Secure`、`HttpOnly`，并在实施前根据 Console 最终域名拓扑选择 SameSite 与 CSRF 防护方案；不得把 Access Token 或 Refresh Token 写入 `localStorage`。

### 密码、邀请与服务身份

- 用户以全局唯一、规范化的邮箱地址登录；显示名可重复。MVP 不支持公共注册、手机号登录、外部身份源、OIDC、SSO、LDAP 或第三方登录。
- 密码使用 Argon2id 哈希，至少 12 个字符，不强制字符类别组合，并拒绝已知泄露密码。
- 无风险事件下不强制周期性改密；发生泄露、高风险登录或管理员强制重置时，要求改密并撤销相关会话。
- Tenant 管理员创建用户只发送一次性、限时激活链接，由用户自行设置密码；管理员不能设置或查看初始密码。
- 服务间采用 OAuth 2.0 Client Credentials。服务 Token 只表示 `client_id` 与显式 `scope`，不得伪造用户、Membership、Tenant。Client Secret 仅在创建或轮换时明文展示一次，平台保存哈希，支持重叠轮换和立即吊销。
- IAM 经版本化 JWKS 发布公钥；私钥只在外部受管密钥存储中保存。

## 授权、租户与数据隔离

请求必须经 Authentication → Identity → Membership → Tenant Resolve → Tenant Context，再执行 Permission、Feature、Quota 和业务逻辑。Tenant Context 需可信、明确、支持跨线程、异步和消息场景传播；不得使用请求中的 `tenantId` 作为安全边界。

RBAC 以 `Membership → Role → Permission` 实施。平台角色与租户角色隔离；Permission 与 Feature 必须可同时校验。所有 Tenant 范围数据表采用 `tenant_id` 与 PostgreSQL RLS；事务级 `app.tenant_id` 缺失时默认拒绝，常规账号无 `BYPASSRLS`。

## 网络、密钥与基础设施

- API Gateway 是唯一公网入口；领域服务不直接暴露公网。
- 生产环境所有通信采用 TLS；服务间 gRPC 使用 mTLS。
- PostgreSQL、Redis、Kafka 与对象存储使用加密连接和各自凭据认证。
- JWT 私钥、数据库密码、Client Secret、Kafka 凭据等由外部密钥管理服务权威托管；Kubernetes 受控同步挂载，虚拟机经受限系统凭据文件注入。密钥禁止进入源码、镜像或普通配置文件。
- Redis 作为 Token 黑名单和会话等安全依赖，生产环境必须高可用并具备自动故障转移。

## API、前端与运行时防护

- Gateway 以 Redis 令牌桶按 IP、Identity、Client、Tenant 限流；策略按环境配置。
- CORS 默认拒绝，仅允许两类控制台和经审核登记的业务微前端来源；禁止携带凭据的通配来源。
- Tenant Console Shell 只在内存保存 Access Token；业务 Remote 只能经 Shell 的认证 API 和共享 HTTP Client 调用服务，不能读取或保存令牌。
- Module Federation Remote 的来源和版本由 Manifest 白名单控制；Tenant 管理员不能录入任意远程脚本地址。
- 所有创建和变更 API 使用幂等键；失败以 `application/problem+json` 返回稳定 `code` 与 `traceId`。

## 数据、日志与审计

平台数据分为公开、内部、机密、敏感个人信息四级；业务模块注册其数据分类并映射到该分级。日志、Trace、审计和 Kafka 事件默认不得包含密码、Access / Refresh Token、Client Secret、完整证件或其他原始敏感个人信息，统一通过字段白名单和脱敏处理。

Audit 服务只追加审计记录，平台或租户管理员不得修改或物理删除。审计保留期由平台级合规配置确定。导出保留任务元数据与审计记录；临时导出文件通过短期签名 URL 获取，并按可配置留存期从对象存储物理删除。

## 安全验证与应急

- CI 执行依赖、镜像漏洞扫描和 Gateway 的 OWASP ZAP 基线扫描；高危和严重漏洞阻断合并与发布。
- RLS 为强制集成测试门禁：应用数据库角色必须无法跨 Tenant 读写改删，缺失 Tenant 上下文默认拒绝。
- 安全事件须关联 `traceId`、影响范围、开始/恢复时间、根因和改进项；变更、漏洞处理、恢复演练和应急响应需形成可审计记录。
