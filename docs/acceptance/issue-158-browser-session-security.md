# 四域双槽位与 API 安全拒绝验收（Issue #158）

## 范围与入口

沿用 ADR 0009、0038、0039 的受控 Origin、Browser Session Slot 和共享认证 Runtime。在同一浏览器上下文中操作真实 Platform Console 与 Tenant Console；成功认证、恢复和登出全部由页面调用正式共享 HTTP Client。攻击探针单独构造非法请求，不向产品接口增加 Cookie、Origin、Fetch Metadata 或 Token 参数。

开发验收需要四域 HTTPS、Vite Console、Gateway 和领域服务已经就绪。使用同一个具有 Platform Role、正式密码及一个有效 Tenant Membership 的验收 Identity；不支持用初始密码代替正式密码，也不自动创建或修改现有 Tenant。先执行 `bash scripts/local-https-development.sh doctor`，环境启动沿用开发文档。

```bash
export SF_SESSION_EMAIL_FILE=/absolute/path/to/platform-email
export SF_SESSION_PASSWORD_FILE=/absolute/path/to/current-platform-password
export SF_SESSION_EVIDENCE_DIRECTORY="$PWD/.scratch/issue-158"
pnpm --dir consoles run verify:local:session-security
```

凭据只从受限文件读取，不放入命令行或证据。缺少文件、服务、证书信任或有效 Membership 时测试失败，不以 Mock、TLS 绕过或 localhost 产品入口代替。未指定证据目录时使用临时目录，并输出 `EVIDENCE:` 路径。

## 可观察行为

- 两个 Console 登录后各自恢复，当前槽位 Cookie 发生轮换，另一槽位 Cookie 保持原值；依次登出 Platform、重新登录 Platform、登出 Tenant，另一侧仍能刷新恢复。已登出侧重新加载保持登录页。
- `__Host-sf_platform_refresh`、`__Host-sf_tenant_refresh` 的实际 Set-Cookie 均来自 API，包含 Secure、HttpOnly、SameSite=Strict、Path=/，没有 Domain。Console 页面、Vite 模块和实际 Remote 资源请求均不携带这两个 Cookie。
- 已登录时通过真实 Tenant 验收入口执行第四域 v1/v2 模块、应用 CSS、解码图片。
- 两个合法 Console Origin 的预检精确许可 Origin 和凭据，保留七种方法、六种请求头、600 秒缓存和 Vary；实际认证响应只暴露 Location、Retry-After。
- 对 refresh、logout 分别验证缺失/错误 CSRF、非 JSON、两个方向的槽位错配、非法请求头/方法、Remote/外站/null 预检，以及来自这些来源的直接 no-cors 攻击。Origin、Cookie、Fetch Metadata 均由浏览器生成；localhost 仅用于外站攻击页面。
- 每个探针都读取 Chromium CDP ExtraInfo 中的实际 HTTP 状态与 CORS 元数据，并检查未设置/清除 Cookie、原 Cookie 未变化；随后两个 Console 都通过真实页面刷新恢复，排除“Cookie 没变但服务端已经撤销”的误判。

共 32 个探针。其中 30 个得到服务端 403；两个未知请求头预检保持既有 Spring 行为：OPTIONS 返回 200，但不许可该请求头，浏览器阻止实际 POST。后者属于 CORS 证据，不能冒充服务端实际操作的 403。其余直接请求/预检均以真实 403 断言，不能仅凭 fetch 抛错通过。

## 修复与 TDD 证据

开发浏览器发现 Remote Origin 预检得到 `502 UPSTREAM_INVALID_RESPONSE`。原因是 DefaultCorsProcessor 返回纯文本 403，随后被 Gateway 的错误规范化 Filter 当成非法上游响应。修复仍使用同一个 CORS Processor 和配置，仅把其拒绝转换成 Gateway 自有的 `403 BROWSER_REQUEST_REJECTED` Problem。

`GatewayJwksRouteTest.rejectedCorsPreflightRemainsABrowserRejectionInsteadOfAnUpstreamFailure` 在真实 HTTP 边界先复现 `expected 403, got 502`，修复后通过。相关 Gateway 路由与 Problem Details 测试共 19 项通过。

```bash
./mvnw -pl gateway -am \
  -Dtest=GatewayJwksRouteTest,GatewayProblemDetailsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 证据与复用边界

`browser-sessions.json` 记录运行模式、浏览器版本、时间、操作结果、脱敏请求/响应及控制台分类；失败保留失败阶段。不会保存 Cookie 值、Token、请求/响应正文、密码或原始控制台文本。匿名恢复 401 和关联攻击探针的 CORS/ERR_FAILED 单独分类，其他 Console 错误使验收失败。另保存两个已认证页面截图。

2026-09-09 的本地验证使用受信四域、Vite Console、真实开发后端及修复后的本机 Gateway，32 个探针和完整会话流程通过。此次未运行 Fresh Compose 全量验收或 Firefox/WebKit/Chrome/Edge 矩阵，不代表 #155 的聚合验收。

同日关闭前复核补齐真实刷新请求的 `sessionSlot` 与 Console Origin 配对断言，解决原有契约静态检查对仅观察刷新 URL 的误判，未修改或豁免门禁。`./mvnw -pl gateway -am verify` 全部通过，包含 Gateway 39 项测试及 4 项集成测试；开发 Chromium 的 32 个探针再次通过，复核证据位于 `.scratch/issue-158/review-fixed/browser-sessions.json`。

现有 `console-authentication.test.mjs` 在创建首个 Tenant 后，以独立浏览器上下文复用同一安全流程；Chromium/Chrome/Edge 使用 CDP 记录直接响应，证据写入现有 `EVIDENCE:` 目录下 `session-security-<channel>/`。原有跨浏览器认证流程同时增加“另一槽位 Cookie 不变”和 SameSite 断言；Firefox/WebKit 不调用 Chromium 专属采集器。既有 Fresh Compose 与浏览器矩阵启动命令保持不变。
