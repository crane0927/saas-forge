# Gateway Context

Gateway 管理平台公开 HTTP 与受控浏览器交付边界；它不拥有身份、Tenant、权益或审计领域事实。

## Language

**Controlled Browser Origin**:
位于同一完全受控可注册根域下、以 HTTPS 提供 Platform Console、Tenant Console Shell、API Gateway 或业务 Remote 的固定浏览器 Origin。
_Avoid_: Arbitrary remote origin, customer-provided origin

**Refresh Token Cookie**:
仅由 API Gateway 的 `api.<root>` Origin 签发和接收、归属于一个 Browser Session Slot 的 host-only Cookie，用于携带 Refresh Token；Console 或业务 Remote 不能读取它。
_Avoid_: Shared domain cookie, browser token store

**Browser Session Slot**:
受控 Console Origin 在 API Origin 上专属的 Refresh Token Cookie 边界；Platform 与 Tenant 槽位分别只定位一个当前 Refresh Token Family，彼此不能刷新或登出对方会话。
_Avoid_: Session instance, shared refresh session, browser token store

**CSRF-Protected Browser Request**:
来自受控 Console、可能改变平台状态的浏览器请求；它通过精确 Origin、Fetch Metadata 和专用请求头证明来源。
_Avoid_: Remote API request, cross-site browser request

**Browser Origin Allowlist**:
由部署期 `browser.rootDomain` 推导的固定 Origin 集合；它不能被 Manifest 或运行时注册扩展。
_Avoid_: Dynamic CORS allowlist, remote API allowlist

**Local Browser Topology**:
用于开发与端到端测试的受控浏览器 Origin 集合，以 `saasforge.test` 保持与生产相同的主机分离模型。
_Avoid_: localhost port topology, production root domain
