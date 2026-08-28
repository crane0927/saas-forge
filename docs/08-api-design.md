# saas-forge API 设计

## 集成边界

`saas-forge` 对外提供 REST + JSON API；业务系统也可通过 Java SDK 接入。业务系统禁止直接读取或写入任一平台服务数据库。

```text
Browser / Business Application
            │ HTTPS + JSON
            ▼
       API Gateway
            │
    ┌───────┼────────┬─────────────┐
    ▼       ▼        ▼             ▼
  IAM   Tenant Access Entitlement Audit
```

API Gateway 是唯一公网入口，负责 TLS 终止、JWT 初步校验、限流、路由、CORS 与 `traceId` 透传。领域服务不直接暴露公网。

Gateway 的用户 Token 强制策略由正式 OpenAPI 操作的 `security` 声明生成或校验，不另维护可漂移的路径列表。必需 `UserBearerAuth` 的操作在转发前完成验签与撤销检查；匿名操作不要求 User Token；登出等同时声明 `UserBearerAuth` 与匿名的操作必须允许请求到达下游以完成 Cookie 清理，不得因可选 Bearer 无效或已撤销而提前拒绝。

## 契约与版本

- OpenAPI 3.1 是正式且受版本控制的 REST 契约；Swagger UI 只可作为查看和调试界面。
- 契约采用 spec-first：先审查 OpenAPI，再生成服务端接口骨架、Java REST Client 与前端 API Client；实现不得反向修改契约。
- 平台自有公共 REST API 只使用 URI 主版本，例如 `/api/v1/...`，不同时使用自定义版本请求头或媒体类型参数。`/oauth2/token`、`/.well-known/jwks.json` 等受标准路径约束的端点保持既有非版本路径。删除、重命名或改变字段类型/语义，收紧既有有效输入，或新增必填字段均为破坏性变更，必须进入新的主版本；同一主版本只允许新增可选字段、枚举值或可选能力等向后兼容变更。
- 内部同步接口使用版本化 Protobuf；Kafka 事件使用 CloudEvents JSON 与版本化类型，例如 `com.saasforge.tenant.suspended.v1`。

## v1 资源边界

以下路径是 v1 的资源分组，具体请求/响应 Schema 以 OpenAPI 3.1 文件为准。

| 路径前缀 | 服务 | 资源与操作 |
|---|---|---|
| `/api/v1/auth` | IAM | 登录、刷新、登出、Tenant 切换、Password Setup Challenge 兑换 |
| `/oauth2/token` | IAM | 仅服务间 Client Credentials；不承载面向用户的第三方授权登录 |
| `/.well-known/jwks.json` | IAM | 版本化 JWKS 公钥发布 |
| `/api/v1/platform/oauth-clients` | IAM | Platform Admin 管理服务 OAuth Client、Secret 轮换、签发恢复和不可逆吊销 |
| `/api/v1/platform` | Tenant Access、Entitlement | Tenant、平台管理员、Plan、Subscription、Feature、Quota 定义和能力注册 |
| `/api/v1/tenant` | Tenant Access | Membership、Organization、Role、Permission、邀请、未认证的 Invitation 激活与租户设置 |
| `/api/v1/runtime` | Tenant Access、Entitlement | 业务系统的 Permission / Feature 查询与 Quota `check`、`consume`、`release` |
| `/api/v1/audit` | Audit | 经过授权的审计查询与导出任务 |

用户 Token 的 Tenant 由已验证的 `membershipId` 决定。用户请求不得通过请求头、查询参数、请求体或任何语义等价别名传入或覆盖 Tenant；这类输入必须作为字段校验失败以 `400` 拒绝，而非静默忽略。Tenant 切换接口只接受目标 `membershipId`，由 IAM 校验后建立新上下文，不接受 Tenant 标识。

Client Credentials 的身份与授权语义只限 `client_id` 与显式 `scope`，不得伪造用户、Membership、Tenant 或用户 RBAC 上下文；缺少所需 scope 时返回 `403`。Service Access Token 使用 `at+jwt` 类型、5 分钟默认有效期以及固定 Claim 白名单，`sub` 必须等于 `client_id`；接收方必须精确校验类型、签名、Issuer、Audience、时间和 Scope。服务可在契约明确的内部调用或可信消息元数据中携带 Tenant Operation Target，但下游必须按 `client_id` 与 `scope` 授权并校验其与目标资源的关系；它不建立 Tenant Context。

OAuth Client 管理只接受 Platform Admin 的 Platform User Access Token。Client 创建、Secret 轮换或签发恢复只在首次成功响应中展示 Secret；它们不适用普通响应体重放，具体接口与安全例外见 [OAuth 2.0 Client Credentials 管理规格](22-oauth-client-credentials-management.md)。

Tenant 切换为 IAM 的 `POST /api/v1/auth/tenant-switches`：请求通过必需的 Refresh Token Cookie 定位当前 `USER_TENANT` Family，Body 只携带目标 `membershipId`，Bearer Access Token 不参与定位。IAM 必须同步向 Tenant Access 分别验证当前与目标 Membership 属于该 Identity、仍启用且所属 Tenant 可访问；实际切换撤销该 Family 切换前签发且未过期的全部 User Access Token，只更新该 Family 的上下文并返回 `204 No Content`。Tenant Console Shell 随后调用刷新接口取得新 Access Token，刷新完成前不得再次切换。Invitation 激活为 Tenant Access 的 `POST /api/v1/tenant/invitation-activations`：它不接受客户端提供的 Tenant 上下文，而是由 Invitation 令牌解析所属 Tenant。初始管理员 Password Setup Challenge 由 IAM 的 `POST /api/v1/auth/password-setups` 匿名兑换；Platform Admin 请求首次投递或重发使用 Tenant Access 的 `POST /api/v1/platform/tenants/{tenantId}/administrator-password-setups`，后者不得返回 Token、邮箱或投递内容。

Password Setup 邮件链接只允许使用 `https://console.<root>/password-setup#token=<token>`，其中 Token 为 43 字符无填充 Base64URL。Token 不得放入查询参数；控制台读取 Fragment 后立即从地址栏移除，并仅通过 `POST /api/v1/auth/password-setups` 的 JSON Body 提交。该端点不接受 Identity、Membership、Tenant 或邮箱字段。

IAM 的 JWKS 响应以 `Cache-Control: max-age=300` 发布。验证方遇到未知 `kid` 时必须受控地刷新 JWKS；常规密钥轮换在新 `kid` 发布满 5 分钟后才能切换签名，旧公钥在切换后至少保留 30 分钟。验证方仍须在每个请求中拒绝已撤销的 `kid`，不得仅依赖 JWKS 缓存结果。

## REST 约定

### 命名、标识与 JSON 表示

- 资源路径使用小写 `kebab-case` 复数名词；JSON 字段与查询参数使用 `lowerCamelCase`；枚举和稳定业务错误码使用 `UPPER_SNAKE_CASE`。`/api/v1/tenant` 是由当前认证上下文解析出的单例 Tenant 作用域，不是集合；其下的真实资源仍使用复数。`auth`、`platform`、`runtime` 与 `audit` 是既定能力边界，不以其单复数形式推断资源语义。
- 独立实体 ID、`Idempotency-Key` 和其他声明为 UUIDv7 的标识符，均使用 RFC 9562 的 36 位、小写、连字符分隔文本形式；拒绝大写、无连字符或其他 UUID 文本表示。
- 时间点使用 UTC RFC 3339 字符串，固定三位毫秒并以 `Z` 结尾，例如 `2026-08-17T09:30:45.123Z`；拒绝无时区或非 UTC 偏移的时间、Unix 时间戳和浮点秒。纯日历日期使用 `YYYY-MM-DD`，不得附加时间或时区。
- 所有精确小数使用非科学计数法的十进制 JSON 字符串，例如 `"12.3400"`；不得使用 JSON number、`NaN` 或 `Infinity`，服务按字段业务精度校验。金额使用 `{"amount":"12.34","currency":"CNY"}`，其中 `currency` 为大写 ISO 4217 三字母代码；不得将金额和币种拼为一个字符串，也不得省略币种。
- JSON `null` 只表示字段已知但没有值，或请求中显式清空可写可空字段；不得用空字符串、零值、空数组或哨兵枚举替代。字段缺失的含义由创建、替换和部分更新语义决定。

### 参数与方法

- 路径参数只定位资源或资源层级，不承载筛选条件、操作选项或秘密。查询参数只表达安全读取的筛选、排序、分页和投影选项；`GET` 与 `HEAD` 不使用请求体。请求头只承载认证、内容协商、幂等、关联与条件请求等跨资源传输元数据。请求体只承载创建、替换、更新的资源表示或明确操作输入，不得重复资源 ID、Tenant 身份、认证信息、分页或排序。
- `POST` 只向集合创建新资源，或创建显式命名的操作/任务资源；客户端不得指定服务器生成的资源 ID，且不得用 `POST` 作通用替换或部分更新。状态转换等非 CRUD 行为必须建模为资源，例如 `POST /subscriptions/{subscriptionId}/cancellations`，而不是动词式路径。
- `PUT` 只完整替换路径定位的既有资源。请求体必须包含全部可写字段；缺字段返回 `400`，目标不存在返回 `404`，不支持隐式创建（upsert）。
- `PATCH` 只使用 `application/merge-patch+json`。请求体仅包含要变更的可写字段；缺失字段保持不变，`null` 显式清空可空字段，向必填或不可空字段写入 `null` 返回 `400`，目标不存在返回 `404`。

### 过滤、排序与分页

- 每个集合只能接受其 OpenAPI 契约明确白名单的 `lowerCamelCase` 过滤参数，例如 `status=ACTIVE`、`createdAfter=...`；不提供自由字段路径、SQL/OData/RSQL 表达式或未声明字段过滤。
- 可选的 `sort` 使用逗号分隔字段；无前缀为升序，`-` 前缀为降序，例如 `sort=-createdAt,name`。每个集合只开放已声明字段，服务始终追加 `id` 作为稳定最终排序键；默认排序也必须由该集合契约明确声明。
- 所有集合查询使用游标分页：`limit` 是正整数，默认 `50`、最大 `100`；超出范围返回 `400`。首页省略 `cursor`；游标由服务端生成且不透明，并绑定资源、筛选条件和排序，格式非法、过期或不匹配时返回 `400`。响应包含 `items`、`nextCursor`、`hasMore`；末页必须返回 `"nextCursor": null` 与 `"hasMore": false`。

### 文件、异步任务与幂等

- v1 不提供通用 `/files` 或上传 API。平台导出一律先创建显式导出任务资源，返回 `202 Accepted` 和任务 `Location`；客户端轮询任务资源，完成后取得短期签名下载 URL。文件字节不经平台 API 中转；任何未来领域文件能力必须另行定义其所有权、类型、大小、病毒扫描与留存规则。
- 创建和其他具有外部可见状态变更的请求必须携带规范 UUIDv7 形式的 `Idempotency-Key`，包括 `PUT`、`PATCH`、`DELETE` 和操作资源的 `POST`。键按外部调用方跨全部状态变更接口唯一：用户令牌使用 `identityId`，服务令牌使用 `client_id`；未认证的 Invitation 激活请求在验证令牌后使用 `invitationId`。同键重试完全相同的请求时，服务原样重放首次完成请求的 HTTP 状态码和响应体，而不重新执行业务操作，首个业务 `4xx` 也须重放。方法、规范化路径或规范化请求体不同的同键请求，以 `409 Conflict` 和 `IDEMPOTENCY_KEY_REUSED` 拒绝。首次请求未完成时的同键重复请求，以 `409 Conflict`、`IDEMPOTENCY_REQUEST_IN_PROGRESS` 和 `Retry-After` 拒绝。仅 `2xx` 和业务 `4xx` 是可重放稳定结果；无持久完成记录的基础设施 `5xx` 不缓存并释放键，已提交业务变更与幂等完成记录必须同一事务写入。请求格式或字段校验 `400` 不创建幂等完成记录，修正后可沿用同一键。幂等记录自首次完成起保留 24 小时，期满后同一键可视为新请求；缺失/空白和格式非法的键分别以 `400` / `IDEMPOTENCY_KEY_REQUIRED` 和 `400` / `IDEMPOTENCY_KEY_INVALID` 拒绝，且不预留键。
- 未认证的 Password Setup Challenge 兑换在验证 Token 后以 `challengeId` 作为调用方作用域，也必须携带规范 UUIDv7 `Idempotency-Key`。创建 Password Credential、消费 Challenge 和记录稳定 `204` 结果必须在 IAM 同一事务完成；相同 Token 与相同 Key 重试时重放 `204`，使用其他 Key 重试已消费 Token 时统一返回 `PASSWORD_SETUP_TOKEN_INVALID`。幂等指纹只绑定方法、规范化路径与 Challenge Token 摘要，不保存或快速哈希新密码；未提交业务结果的 `5xx` 不缓存。
- OAuth Client 创建、Secret 轮换与 Secret Issuance Recovery 是摘要-only 安全例外：操作终态永久保留但不保存或重放含 Secret 的响应，同键重放返回 `409 / CLIENT_SECRET_ALREADY_REVEALED`；恢复是引用原操作的新幂等操作。吊销不含 Secret，继续按普通幂等规则重放 `204`。完整规则见 [Client Credentials 管理规格](22-oauth-client-credentials-management.md)。
- Tenant Administrator Initialization 在 Quota 已扣减但本地激活事务失败后保留原幂等键和根工作流；补偿未完成时返回可重试的 `503 / TENANT_ADMIN_INITIALIZATION_COMPENSATING`。补偿成功后，原键稳定重放 `409 / TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED`，客户端必须以新键启动具有全新下游子操作 ID 的根工作流；不得让原键在同一根工作流内生成无限次 Quota 尝试。
- Tenant Suspension 和恢复是普通 `5xx` 释放幂等键规则的安全例外：Tenant Access 在远程调用前持久化根工作流和稳定内部请求 ID，同 Key 在处理中返回 `503` 与 `Retry-After`，完成后重放 `200`。Fence 已建立后自动恢复耗尽时不释放工作流或 Fence；必须由 Platform Admin 通过幂等的 Suspension Recovery 操作恢复原流程。
- Tenant Suspension v1 只做兼容性新增：保留现有 Suspension/恢复路径、请求与 `200 Tenant`，新增 `503`、`Retry-After`、新 Problem codes 以及 `POST /api/v1/platform/tenants/{tenantId}/suspension-recoveries`。现有 `502/504` 声明不从 v1 删除，但耐久工作流中的暂时依赖失败统一映射为 `503 PENDING`。历史 compatibility baseline 必须保持逐字节不变，OpenAPI、生成接口、Gateway 路由和安全分类必须一起通过兼容性门禁。
- Platform Admin 主动请求初始管理员 Password Setup 投递或重发时，只有 SMTP 明确接受邮件，或 IAM 确认 Identity 已有有效 Password Credential，才返回 `204 No Content`。投递尚未完成时保留根工作流并返回 `503 / PASSWORD_SETUP_DELIVERY_PENDING` 与 `Retry-After`，后台继续同一 `DeliverPasswordSetup requestId`；投递完成后相同外部 Key 稳定重放 `204`。该端点不返回 `202`，因为当前切片不创建可查询 Job 资源；`IDENTITY_CREDENTIAL_RECOVERY_REQUIRED` 是稳定 `409`，不得后台转为成功。
- Quota 的 `consume` 与 `release` 额外要求调用方稳定生成的 `operationId`，`check` 不需要；它独立于 HTTP `Idempotency-Key`，用于绑定业务资源的计量动作并避免跨服务重试或补偿链路重复扣减、释放。其作用域与冲突规则以[核心领域契约](17-core-domain-contracts.md#quota)为准。

### 关联与内容协商

- W3C Trace Context 是唯一跨边界关联机制。Gateway 接受有效的 `traceparent`、`tracestate` 并透传；它们缺失或无效时新建 Trace。所有服务必须继续该上下文；Problem Details 必须返回 `traceId`。`requestId` 仅用于内部日志，不作为公共 HTTP 契约，也不增加 `X-Request-ID` 或其他业务自定义关联头。
- JSON 请求使用 `Content-Type: application/json`，`PATCH` 使用 `application/merge-patch+json`。JSON 成功响应使用 `application/json; charset=utf-8`，错误响应使用 `application/problem+json; charset=utf-8`。缺省 `Accept` 时按相应默认类型返回；不接受的 `Accept` 返回 `406`，不支持的请求 `Content-Type` 返回 `415`。不得使用厂商媒体类型或媒体类型参数承载 API 版本。

### 成功与失败响应

- 成功响应不使用 `code`／`message`／`data` 等通用外层包装。`200` 直接返回资源表示或操作结果；`201` 直接返回新资源并带 `Location`；`202` 直接返回 Job 资源并带 `Location`；`204` 不得携带响应体。`Location` 必须是目标资源的规范 API 绝对路径引用，不含主机、片段或查询参数，且与后续 `GET` 路径完全一致。
- 集合成功响应直接返回 `{ items, nextCursor, hasMore }`。三个字段始终存在，`items` 始终为数组；`hasMore = true` 时 `nextCursor` 必为非空字符串，`hasMore = false` 时 `nextCursor` 必为 `null`。
- 异步工作统一称为 Job，以避免与官方 Example 的业务 `Task` 混淆。Job 的状态只能为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`；前两者为非终态，后两者为终态。Job 必含 `id`、`status`、`createdAt`、`startedAt`、`completedAt`、`failure`：`createdAt` 非空，`startedAt` 仅在 `QUEUED` 为 `null`，`completedAt` 仅在终态非空，`failure` 仅在 `FAILED` 为非空 Problem Details。轮询 `GET` Job 始终按资源读取返回 `200`，即使 Job 已失败；具体 Job 自行定义其成功结果字段，不使用无类型的通用 `result` 对象。
- 失败响应采用 `application/problem+json`，并始终包含 `type`、`title`、`status`、`code`、`detail`、`traceId`。`type` 由 `code` 唯一派生为 `urn:saasforge:problem:{lower-kebab-case-code}`；`status` 必须等于 HTTP 响应状态。`title`、`detail` 及字段校验项的 `detail` 固定使用英文，只供人读；客户端只按全大写 `UPPER_SNAKE_CASE` 的 `code` 分支，不得解析这些文本。`traceId` 是非全零的 32 位小写十六进制 W3C Trace ID，不是 UUID。
- 请求格式或字段校验失败使用 `400` Problem Details，并额外包含非空 `errors` 数组；每项都有指向无效输入的 JSON Pointer `pointer`、稳定 `code` 与英文 `detail`，不得回显输入值或敏感数据。其他 Problem Details 不包含 `errors`，也不返回可能泄露查询参数的 `instance`。
- [OpenAPI 公共组件](../contracts/openapi/common.yaml)提供可复用 Schema、Response、Header 以及分页、Job、业务拒绝与字段校验的正反例。资源契约必须复用这些组件，并可通过 `allOf` 收窄 `Page.items` 或为具体 Job 添加成功结果字段。

## 认证、来源与限流

- 用户请求使用约 15 分钟的 JWT Access Token；刷新令牌仅由 `api.<root>` 以 `__Host-sf_refresh; Secure; HttpOnly; SameSite=Strict; Path=/` Cookie 携带，且不设置 `Domain`。
- Client Credentials 按 OAuth 2.0 标准实现；服务令牌的身份与授权语义只限 `client_id` 与显式 `scope`，不建立用户 Tenant Context，也不得伪造用户、Membership、Tenant 或用户 RBAC 上下文。
- CORS 默认拒绝。各环境由非敏感部署配置 `browser.rootDomain` 推导精确 Origin：API Gateway 仅允许 `https://platform.<root>` 与 `https://console.<root>` 的凭据型请求，允许 `GET`、`HEAD`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS` 方法和 `Authorization`、`Content-Type`、`Idempotency-Key`、`X-SF-CSRF`、`traceparent`、`tracestate` 请求头，只暴露 `Location`、`Retry-After`，预检缓存 10 分钟并返回 `Vary: Origin`。`https://remote.<root>` 不可直接调用 API；Remote 静态资源仅允许 `https://console.<root>` 无凭据加载。未匹配 Origin 不返回 CORS 许可，禁止通配符、`null` Origin 和 Manifest/运行时扩展白名单。
- 所有浏览器非安全方法，以及 `/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/auth/logout`，必须使用 `application/json` 并带 `X-SF-CSRF: 1`。Gateway 仅接受 `Origin` 为 `https://platform.<root>` 或 `https://console.<root>` 的此类请求，拒绝 `remote.<root>` 和外站 Origin；`Sec-Fetch-Site: cross-site` 一律拒绝，缺失 Fetch Metadata 时仍以 Origin 精确校验。Client Credentials 服务请求不携带浏览器 Cookie，不适用该校验。
- Gateway 基于 Redis 令牌桶，按 IP、Identity、Client、Tenant 维度限流；阈值由环境配置，不写死在代码中。

## 业务能力与前端模块注册

业务模块通过 Client Credentials 注册版本化 Manifest，至少声明：模块标识、远程入口、页面与菜单、所需 Permission、Feature、Quota 定义。CI 是注册主体；平台管理员仅审核、启停和查看，不能手工登记任意远程脚本地址。

业务 Permission、Feature、Quota Definition 也通过该注册契约进入平台。Tenant Console Shell 依据当前用户权限、Tenant 权益和已审核 Manifest 决定菜单及页面可见性；后端仍须独立执行授权与权益校验。
