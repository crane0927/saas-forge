# Issue #171：Platform 当前身份验收

## 2026-09-11 原生开发环境

实现提交：`cc151d1`。服务由 IDE 运行，两个 Console 使用 `pnpm run dev`，
浏览器通过受信 HTTPS Edge、Gateway 和真实服务验收；未使用 Mock 或忽略证书错误。

### 已通过

- Chromium 与 Google Chrome 均完成中文登录（200）、Current Session 读取（200）、
  页面刷新恢复、关闭并重开持久浏览器上下文后的恢复、英文重新读取、退出（204）及
  退出后刷新仍匿名。Current Session 校验 UUIDv7、与凭据文件一致的邮箱、
  `platformAdmin=true` 和 `Cache-Control: no-store`，恢复前后 Identity 一致。
- Google Chrome `153.0.8010.36` 复用既有 `verifyBrowserSessions` 与
  `verifyApiSecurity`，开发模式安全验收通过：双槽位登录、独立刷新、各自退出不影响
  另一槽位；Cookie 属性、CSRF、Content-Type、槽位不匹配、CORS 拒绝及恢复；
  Remote 静态资源与两个 Console 的受信 WSS HMR 检查通过，无非预期页面错误。
  脱敏原始记录位于本机 `/tmp/issue-171-security-cd9F8H/browser-sessions.json`。
- 当前身份聚焦验收脚本位于本机 `/tmp/issue-171-live-session.mjs`；安全验收包装脚本
  位于 `/tmp/issue-171-live-security.mjs`。临时路径不保证长期保留。

### 本轮排障与失败记录

- 原 HTTPS 入口连接被关闭；经用户授权仅重启 `compose-local-https-edge-1` 后，
  真实浏览器 HTTPS 恢复。Gateway 新路由的匿名读取随后返回 401。
- 首次登录验收失败为 401 `AUTHENTICATION_FAILED`：用户提供的凭据文件包含
  `email`、`password` 两个字段，临时脚本误把整份文件作为密码。按字段读取后通过，
  未修改账号或密码，未输出凭据。
- 安全验收首次未设置 `SF_SECURITY_EDGE_CONTAINER`，缺少直接 CORS 拒绝证据而失败；
  显式指定当前 Edge 后，完整安全验收通过。不能将首次失败记录计为通过。

### 未执行及完成边界

- 本轮使用已有可正常登录账号，未验证必要首次改密、登录保护阈值及全部故障恢复分支。
- 尚未运行本切片 Fresh Compose 浏览器验收。现有验收入口要求 443 空闲；当前由
  用户开发环境 Edge 占用，不能自行停止或覆盖。Fresh Compose 使用随机项目和新卷，
  仅清理自己的环境；运行前仍需协调临时释放 443。
- 错误 Token、授权拒绝与迟到响应已有聚焦自动化证据；本轮真实成功路径与开发模式
  安全证据不能替代 Fresh Compose 和未执行的场景。Issue #171 暂不声明完整验收通过。

此前全前端 workspace 验证通过。完整 Maven verify 曾在最终 SDK 发布边界检查失败，
移除不需要的 SDK 发布标记后，相关单元测试及剩余 quality-gates 集成检查通过；
没有将其记为一次从头到尾重新执行成功的完整 Maven 验证。
