# Console 认证 Runtime 与浏览器会话规格

**状态：设计已确认，正式 OpenAPI、Gateway/IAM、共享前端包、两个 Console 接入与 Fresh Compose 浏览器验收均尚未实现。本文不是完成证据。**

本规格定义 Platform Console 与 Tenant Console Shell 共用的认证状态机、HTTP Client、Problem Details 映射、路由守卫、全局导航、错误边界和浏览器验收语义。浏览器会话决策见 [ADR 0038](adr/0038-browser-sessions-use-intent-bound-slots.md)，共享前端边界见 [ADR 0039](adr/0039-consoles-share-one-authentication-runtime.md)，视觉与交互继续遵守 [Design System 规范](25-design-system.md)。

## 1. 目标与非目标

目标：

- Platform 与 Tenant 在同一浏览器配置中可同时登录、独立刷新、独立登出；
- 两个 Console 共用同一状态转换、HTTP、Problem、导航和错误恢复实现；
- Access Token 只位于内存，Refresh Token 只位于 API Origin 的 HttpOnly Cookie；
- 浏览器正常请求与服务端拒绝都有直接测试，不以前端行为代替安全边界。

非目标：

- 不在本切片实现 Manifest、Module Federation 或 Remote 动态导航；
- 不建立新的前端遥测平台；
- 不同时交付完整 `zh-CN`/`en-US` 资源与翻译键门禁；
- 不提供跨槽位“全部登出”、通用网络自动重试或臆造的前端统一超时。

## 2. 术语与不变式

- **Browser Session Slot** 是 Gateway 浏览器交付边界；它不是 Refresh Token Family，也不是前端对象实例。
- **Login Context Intent** 只由登录 `contextType` 表达；**Session Slot Selector** 只由刷新和登出 `sessionSlot` 表达。
- `PLATFORM` 槽位使用 `__Host-sf_platform_refresh`，`TENANT` 槽位使用 `__Host-sf_tenant_refresh`。
- Platform Origin 只能提交 `PLATFORM`，Tenant Origin 只能提交 `TENANT`；Origin 不代替 Role、Membership 或 Family Purpose 验证。
- Initial Credential Session 使用 Platform 槽位；Tenant Context 选择与切换使用 Tenant 槽位。
- 每个页面 Realm 仅一个 Console Authentication Runtime；每个 Runtime 仅持有宿主固定 Intent 对应槽位的 Access Token。
- Token、密码、Membership 候选、Problem 与返回路径不得进入浏览器持久存储。

## 3. 包与所有权

| 边界 | 拥有 | 不拥有 |
|---|---|---|
| `@saas-forge/app-runtime` | Runtime Config、纯 TypeScript 认证状态机、内存 Token、`ConsoleApiClient`、Problem 规范化 | React、页面、导航视图、全局 CSS |
| 共享 React Shell 包 | Runtime Provider、认证路由守卫、全局导航组合、根/路由错误边界 | Token 持久化、领域 API、Manifest/Remote 生命周期 |
| `@saas-forge/design-system` | 认证、导航、错误与恢复的唯一视觉/交互组件 | 认证事实、HTTP、路由决策 |
| Console 宿主 | 固定 Intent、应用名称、本地路由表、本地导航项与默认首页 | 第二套认证/HTTP/错误实现 |

`ConsoleApiClient` 以生成 Client 作为内部契约实现，只暴露正式类型化 operation。消费者不得设置任意 Base URL、Cookie、`Origin`、Fetch Metadata、`Authorization` 或安全请求头，也不得获得携带凭据的通用 `fetch`。HttpOnly Cookie、`Origin` 和 Fetch Metadata 可保留在正式安全契约中，但不得出现为 Console 或 Remote 必须伪造的业务调用参数。

## 4. 浏览器会话协议

### 4.1 Cookie 与 Intent

- 登录沿用现有 operation 与可选 `contextType`，Tenant 缺省语义保留；两个官方 Console 都必须显式提交宿主固定 Intent。
- 刷新和登出的 JSON Body 新增必填 `sessionSlot: PLATFORM | TENANT`。浏览器可同时发送两枚 Cookie，IAM 只能读取显式选中的槽位并校验 Family Purpose。
- 登录发现所选槽位已指向可用 Family 时，在验证新密码前返回 `409 / SESSION_SLOT_ALREADY_ACTIVE`；切换账号必须先显式登出。
- 无效或过期的所选 Cookie 可清除后继续正常登录；旧 `__Host-sf_refresh` 必须清除且不迁移 Family。
- 普通登出只撤销所选 Family 与请求携带的当前 Access Token，不影响另一槽位。

### 4.2 服务端校验分层

- Gateway 对全部浏览器非安全方法以及登录、刷新、登出统一校验 JSON Content-Type、`X-SF-CSRF: 1`、精确受控 Origin 与 `Sec-Fetch-Site != cross-site`。
- IAM 校验 `platform.<root> ↔ PLATFORM`、`console.<root> ↔ TENANT`，并校验所选 Cookie、Family Purpose 与 operation 语义。
- Origin、Intent/Slot、CSRF、Fetch Metadata 或 Content-Type 安全拒绝对外统一为 `403 / BROWSER_REQUEST_REJECTED`，不暴露具体失败项；具体原因只进入脱敏服务端日志。
- 正常客户端发送正确请求不是安全验收；每一拒绝分支都必须有 Gateway/IAM 直接测试。

### 4.3 v1 迁移

该协议保留现有认证路径，但修改 Cookie/security 语义并为刷新、登出新增必填 `sessionSlot`，因此是破坏性 v1 变更。实施切片必须同时：

1. 评审正式 OpenAPI 变更及生成服务端/客户端影响；
2. 保持所有历史 compatibility baseline 逐字节不变；
3. 使兼容门禁只放行 ADR 0038 列明的 operation、字段和 security 变化；
4. 原子升级 Gateway、IAM、生成 Client、两个 Console、E2E 脚本与所有第一方消费者；
5. 在发布说明中显式列出外部 v1 消费者中断风险。

本文批准设计，不表示当前 `contracts/openapi/v1.yaml` 已实现该迁移。

## 5. 状态机

### 5.1 稳定会话状态

| 状态 | 可持有的敏感内存 | 允许的下一步 |
|---|---|---|
| `anonymous` | 无 | 登录 |
| `passwordChangeRequired` | Platform 受限 Cookie（不可读） | 首次改密、登出 |
| `contextSelectionRequired` | Tenant 受限 Cookie（不可读）与当前页内存候选 | 选择 Membership、登出 |
| `authenticated` | Access Token、计算后的内存到期时间 | 业务请求、刷新、登出；Tenant 可切换上下文 |
| `logoutPending` | 无 Token；只有不敏感槽位标记 | 重试登出 |

状态机使用纯 TypeScript 判别联合、Reducer 和显式转换函数，不引入状态机依赖。一个稳定状态同时最多只有一个在途转换：恢复、登录、刷新、首次改密、Context 选择、Tenant 切换或登出。类型必须阻止同时登录/登出、无 Token 发送受保护请求、或从已提交 Tenant 切换回滚到旧 Token。

### 5.2 启动与恢复

1. Runtime Config 失败时继续使用现有失败关闭 Bootstrap，不创建认证 Runtime。
2. Runtime Config 成功后，根 Provider 创建宿主固定 Intent 的唯一 Runtime。
3. `logoutPending` 存在时禁止恢复，只重试所选槽位的登出。
4. 否则冷启动自动执行一次所选槽位刷新：成功进入相应稳定状态，无效会话进入 `anonymous`，可恢复 `409/503` 或网络不可判定进入独立恢复界面。
5. 不使用后台计时器持续刷新。下一业务请求前发现 Token 剩余不超过 30 秒时，先刷新。

### 5.3 登录、密码与 Context 选择

- 共享登录页由宿主注入必填 Intent，不向普通用户提供 Platform/Tenant 切换器。
- 密码仅位于当前表单内存，每次登录请求完成、取消或离开页面后清空；邮箱可保留在当前表单，不广播或持久化。
- `PASSWORD_CHANGE_REQUIRED` 只进入 Platform 首次改密路由；`CONTEXT_SELECTION_REQUIRED` 只进入 Tenant Membership 选择路由。
- Membership 候选只位于发起标签页内存，不广播。选择成功取得 Access Token 后才广播已认证状态。

### 5.4 Tenant Context Switch

Tenant Context Switch 是单一不可回滚的客户端转换：

1. 在 Tenant 槽位锁内以稳定 Idempotency-Key 调用 Switch；
2. 收到 `204` 后立即清除旧 Access Token、阻止业务请求，且只允许继续刷新；
3. 刷新可恢复失败时保留“切换已提交，等待刷新”状态，不恢复已被服务端撤销的旧 Token；
4. 成功取得新 Token 后才广播新状态，并原子更新路由可用性、导航与 Tenant Brand Profile。

### 5.5 登出

- 登出请求只针对当前槽位，并在同 Origin 标签页间串行化。
- 成功时清除内存 Token、`logoutPending` 和相应非敏感协调状态。
- 网络失败或服务端未确认时，立即清除内存 Token，持久化当前槽位的 `logoutPending`，广播会话结束意图并阻止冷启动刷新；界面不得伪称服务端已完成登出，只允许重试。

## 6. 同 Origin 多标签页协调

- 登录、刷新、登出、首次改密、Context 选择和 Tenant 切换共用按 `apiBaseUrl + sessionSlot` 派生的浏览器原子锁；不得以 Token 或 Identity 构造锁名。
- 锁持有者完成会话变更后，通过同 Origin 消息发布 Access Token、到期时间、Context 类型、槽位单调代次以及刷新成功/会话结束/授权失效事件。
- 不广播邮箱、密码、Refresh Token、Membership 候选、原始 Problem、页面路径或错误详情。
- 每次会话变更在锁内递增持久化的非敏感槽位代次；标签页只接受代次更新的消息，登出或授权失效后到达的旧刷新结果必须丢弃。
- 原子锁或跨标签页消息能力不可用时，客户端不得弱化服务端校验；IAM Refresh Rotation Lease、幂等键和 Family 事务是最终并发边界。

## 7. HTTP、刷新与幂等

### 7.1 请求规则

- Client 固定使用 Runtime Config 中经验证的 HTTPS API Origin、`credentials: include`、JSON Content-Type 与 `X-SF-CSRF: 1`。
- Token 到期时间使用响应接收时间与 `expiresIn` 计算，不解析 JWT。业务请求前剩余不超过 30 秒时先刷新。
- 普通 API 首次 `401` 最多发起一次共享刷新；刷新成功后，`GET`/`HEAD` 可重放一次，变更请求只有在原 Idempotency-Key 不变、请求体可重建时才可重放一次。
- 重放后再次 `401` 结束当前会话。普通领域 `403` 不刷新、不登出；Refresh 的会话失效 `401` 或授权上下文丢失 `403` 结束所选会话。
- Refresh 的可恢复 `409/503` 保留会话事实，并按 `Retry-After` 提供有界手动恢复；不运行无限刷新或网络重试循环。
- 不设置无证据的统一前端超时。Client 支持 `AbortSignal`；取消结果未知的变更请求后必须保留原幂等操作句柄。

### 7.2 幂等操作句柄

共享 Client 为一次逻辑操作生成规范 UUIDv7 Idempotency-Key，并将它封装为不透明操作句柄。自动重放和用户对同一未决操作的重试必须复用该句柄；只有用户明确放弃旧操作并创建新逻辑操作时才生成新 Key。需要恢复已持久服务端工作流的明确领域流程可向 Client 提供既有 Key；普通页面不得自行格式化 UUID。

## 8. Problem Details 与运行时信任边界

- 客户端只按稳定 `code` 与发起 operation 分支，不解析 `title` 或 `detail`。
- 共享层严格校验登录/刷新/Context 选择的鉴别联合、Token 字段、`expiresIn`、Membership 候选边界、Problem Details 必填字段、字段错误扩展与实际使用的 `Retry-After` 等响应头；失败时不得保存 Token。
- 普通业务响应继续使用生成 Client 类型，不在前端重复实现全部 JSON Schema。
- 共享层只映射字段校验、认证、传输与基础设施语义；领域页面映射自己的业务 `code` 与操作建议。
- 非 Problem、畸形 JSON、HTML 或不受支持媒体类型安全归一为 `INVALID_SERVICE_RESPONSE`；无法建立请求或网络不可判定归一为 `NETWORK_UNAVAILABLE`。原始响应不进入生产 UI、跨标签页消息或错误 Reporter。
- 映射结果输出稳定本地语义键与安全参数；当前可使用已有中文表现，但完整中英文资源与一致性门禁属于独立国际化切片。

## 9. 路由、导航与错误边界

- 共享 Shell 维护路由矩阵：`anonymous` 只进入登录，`passwordChangeRequired` 只进入 Platform 首次改密，`contextSelectionRequired` 只进入 Tenant 选择，`authenticated` 才能进入受保护路由和全局导航。
- 认证依赖的可恢复 `409/503` 或网络不可判定显示独立全页恢复界面，保留已知会话事实，不伪装成未登录。
- 未认证进入受保护页时，只在内存保留经验证的应用相对路径；登录成功后返回，页面重载导致路径丢失时进入宿主默认首页。不接受绝对 URL、协议相对 URL 或外部 Origin。
- 本切片只建立两个 Console 的共享导航框架、认证状态接入和宿主本地导航项；Manifest/Remote 注册留给第 3 阶段。菜单可见性永远不代替服务端授权。
- 错误分为三层：根 Runtime 崩溃只提供安全重载；路由/未来 Remote 渲染失败隔离当前模块并允许返回导航或重载模块；请求 Problem 是正常页面状态，不抛入 React Error Boundary。
- 生产错误边界不直接输出原始 Error、响应体或堆栈；可选脱敏 Reporter 只接收错误分类、应用/路由标识和已有 `traceId`。

## 10. 实现前置安全缺口

当前仓库中，Tenant Context Switch 与 Password Setup 已有显式浏览器来源校验调用，但登录、刷新和登出尚无直接实现证据证明其完整执行 JSON、CSRF 值、精确 Origin 和 Fetch Metadata 拒绝。共享 Client 发送正确请求不能弥补服务端缺口；Gateway/IAM 安全切片必须在两个 Console 真实浏览器验收前完成并提供正向与每一拒绝分支的测试。

## 11. 验收矩阵

### 11.1 聚焦测试

- 纯状态转换覆盖全部稳定状态、在途操作、非法转换、迟到代次和登出未决；
- HTTP 测试覆盖 30 秒提前刷新、单次 `401` 刷新/重放、幂等句柄复用、不可重放变更与取消后结果未知；
- Problem 测试覆盖完整/畸形/非 Problem 响应、字段错误、`Retry-After`、脱敏和未知错误码；
- Gateway/IAM 聚焦测试覆盖双 Cookie 设置/清除、Family Purpose、旧 Cookie 清理、已活动槽位冲突，以及错误 Origin/Intent、CSRF、Fetch Metadata 和 Content-Type 拒绝。

### 11.2 真实浏览器与 Fresh Compose

- 在 `platform.saasforge.test`、`console.saasforge.test` 与 `api.saasforge.test` 的本地受信 TLS 拓扑同时登录 Platform 与 Tenant，分别刷新、分别登出，证明槽位与内存 Token 不串扰；
- 在同 Origin 多标签页制造并发刷新、登出与迟到消息，证明唯一执行者、代次拒绝和 IAM Lease 回退；
- 验证 Tenant Switch `204 → Refresh`、中间故障恢复与旧 Token 不可回滚；
- 检查 `localStorage`、`sessionStorage`、IndexedDB、Cookie 可读视图、广播消息和生产日志，证明 Token、密码与 Membership 候选未持久或泄露；
- 根错误、路由错误和请求 Problem 分层验证，同时覆盖键盘、焦点、读屏状态与窄屏布局；
- 从全新 Compose 数据卷执行最终产品路径，不以 Mock、curl、生成 Client 或单一应用构建代替。

Chromium、Firefox 与 Playwright WebKit 均阻塞核心认证行为，视觉快照只由 Chromium 维护；Chrome 与 Microsoft Edge 实机渠道作为发布兼容门禁。浏览器缺少原子锁或跨标签页消息能力时，必须验证服务端 Lease 回退，不得静默跳过。

## 12. 交付顺序与完成边界

交付按以下依赖顺序拆分：

1. ADR、术语与正式认证契约；
2. Gateway/IAM 双槽位及浏览器安全拒绝；
3. 无 UI 的认证状态机、HTTP Client 与 Problem 映射；
4. 共享 React Provider、路由守卫、导航和错误边界；
5. Platform Console 接入；
6. Tenant Console 接入及 Tenant Switch；
7. 多 Origin、多标签页与 Fresh Compose 聚合浏览器验收。

每个切片只能声明自己的聚焦完成证据。只有第 1～7 项全部完成，且最终验收证明两个真实 Console 在受控 TLS/Origin 拓扑共用同一实现、会话槽位与内存 Token 互不串扰时，[MVP 开发计划](16-mvp-development-plan.md)对应项才能勾选。
