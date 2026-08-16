# 浏览器界面使用受控 Origin

Platform Console、Tenant Console Shell、API Gateway 和业务 Remote 分别固定为 `https://platform.<root>`、`https://console.<root>`、`https://api.<root>` 与 `https://remote.<root>/<module>/<version>`，其中 `<root>` 是平台完全受控的可注册根域。业务 Remote 不接受任意外部域名；该拓扑在保持独立部署的同时，使认证 Cookie 可限定为 API 的 host-only Cookie，并为后续的精确 CORS 与 CSRF 策略建立可审计边界。

Refresh Token 固定使用由 `api.<root>` 签发的 `__Host-sf_refresh` host-only Cookie，属性为 `Secure`、`HttpOnly`、`SameSite=Strict` 和 `Path=/`，且不设置 `Domain`。Platform Console 与 Tenant Console Shell 只能通过受控的跨 Origin 请求携带该 Cookie，不能读取或向其他子域扩散。

浏览器变更请求使用无状态 CSRF 防护：必须为 JSON 并带 `X-SF-CSRF: 1`，其 `Origin` 只能是 `platform.<root>` 或 `console.<root>`；Gateway 拒绝 `remote.<root>` 和所有外站 Origin，并以 `Sec-Fetch-Site` 拒绝 `cross-site`。不带浏览器 Cookie 的 Client Credentials 服务请求不适用此规则；Remote 必须通过 Shell 的共享 HTTP Client 发起请求。

每个环境以非敏感部署配置 `browser.rootDomain` 推导固定 CORS 值。API Gateway 仅对 `https://platform.<root>` 与 `https://console.<root>` 允许凭据型 CORS；允许的方法为 `GET`、`HEAD`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS`，请求头为 `Authorization`、`Content-Type`、`Idempotency-Key`、`X-SF-CSRF`、`traceparent`、`tracestate`，只暴露 `Location` 与 `Retry-After`，预检缓存 10 分钟并返回 `Vary: Origin`。Remote 静态资源仅允许 `console.<root>` 无凭据加载。未匹配 Origin 不返回 CORS 许可，禁止通配符、`null` Origin 和 Manifest/运行时扩展。

开发与端到端测试也采用相同主机分离模型，使用 `platform.saasforge.test`、`console.saasforge.test`、`api.saasforge.test` 与 `remote.saasforge.test`。这些名称映射到 `127.0.0.1`，经本地受信 TLS 反向代理提供 HTTPS，并以 `browser.rootDomain=saasforge.test` 推导 Cookie、CSRF 和 CORS 配置；不得用不同 `localhost` 端口替代该验收拓扑。
