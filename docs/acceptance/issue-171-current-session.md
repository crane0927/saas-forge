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

## 2026-09-11 Fresh Compose

用户停止本地 HTTPS Edge 并释放 443 后，执行既有入口：

```bash
SF_ACCEPTANCE_TLS_CERT=<受信证书绝对路径> \
SF_ACCEPTANCE_TLS_KEY=<私钥绝对路径> \
bash scripts/verify-console-authentication-e2e.sh --product
```

- 最终进程退出码为 0；Chrome 产品测试 **33/33**，失败、跳过、取消均为 0；
  `console-browser-chrome` 门禁通过。机器记录见
  [fresh-compose-run.json](assets/issue-171/fresh-compose-run.json)。
- 使用随机项目 `saas-forge-console-1789114665-6219-bd76b6` 的全新数据卷，验证
  首次登录要求改密且不签发 Access Token、改密 204、重新登录、中文当前身份、英文
  重新读取及双槽位独立刷新/退出。复用测试还验证 Membership 选择、Tenant Switch、
  未决 Logout、服务端 Lease 回退、正式 Client 恢复和安全存储等既有边界。
- 产品成功路径经过生产 Console、受信 HTTPS、Gateway 与真实服务；可恢复故障、
  迟到响应和错误页面用例中沿用既有受控故障注入，不能描述为全部故障都由真实服务产生。
- 第一次运行 7 通过、1 失败：语言切换后焦点仍在语言选择器，登录前测试却要求标题
  获焦。仅为该次检查指定 `focusedElementId: 'console-locale'` 后重跑通过；
  标题、ARIA 播报、Tab 顺序和后续路由标题焦点检查保留，修正经复审无问题。
- 记录中的基线为 `b807ce1`，`dirty=true` 对应上述尚未提交的焦点测试修正。
  `--product` 复用已有构建，本轮不包含 Maven/workspace 重跑。
- 验收结束后已确认该随机项目的容器及数据卷均不存在。

### Issue #171 验收映射与边界

| 验收项 | 证据 |
| --- | --- |
| 正式 Current Session、共享 Client、权威身份与授权展示 | IAM HTTP 测试、共享 Client 测试；原生 Chrome 与 Fresh Compose 实际页面读取 |
| 既有认证 Runtime、首次改密、刷新、退出及恢复 | Fresh Compose 首次改密与双槽位恢复；原生 Chrome 浏览器重启；Runtime 错误/恢复测试 |
| Tenant Context 与 Accessible Memberships 的复用和边界 | 聚焦 HTTP 授权测试；Fresh Compose Membership 选择与 Tenant Switch |
| 读取和业务分别授权、错误 Token、迟到响应隔离 | 聚焦 HTTP 测试及共享 Client 的成功/错误正文延迟回归；Fresh Compose 既有恢复与迟到认证用例 |
| 真实受信 HTTPS、双语与浏览器安全 | 原生 Chrome、Fresh Compose 33/33 和双槽位安全探针；中文主路径与英文代表操作 |

本切片的实现、聚焦测试和上述产品验收已完成。没有对原生环境账号主动触发登录保护
阈值，也未追加所有故障的真实基础设施中断矩阵；相关稳定反馈沿用已有 Runtime/HTTP
测试。父 Issue #170 的资源页面、初始化产品闭环及第 2 阶段其他验收不因此完成。

此前全前端 workspace 验证通过。完整 Maven verify 曾在最终 SDK 发布边界检查失败，
移除不需要的 SDK 发布标记后，相关单元测试及剩余 quality-gates 集成检查通过；
没有将其记为一次从头到尾重新执行成功的完整 Maven 验证。
