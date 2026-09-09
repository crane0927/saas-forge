# 四域跨浏览器聚合验收（Issue #159）

## 入口与边界

父规格为 #155。开发与 Fresh Compose 共用 `static-remote.test.mjs`、`browser-session-security.mjs` 和 `browser-api-security.mjs`；成功路径使用真实 Console、受信 TLS Edge、Gateway/真实服务及版本化 Remote。此验收不交付 Manifest、业务 Remote、生产/CDN 或完整升级回退治理，不修改或关闭父 Issue。

开发环境先按 `docs/local-static-remote-development.md` 启动四域与两个 Vite Console。提供具有 Platform Role、正式密码和有效 Tenant Membership 的同一验收账号；凭据仅通过受限文件读取。新观测模块要求开发 Edge 加载当前 Compose 配置，不能继续使用修改前的进程。

```bash
export SF_SESSION_EMAIL_FILE=/absolute/path/to/email
export SF_SESSION_PASSWORD_FILE=/absolute/path/to/current-password
export SF_BRAND_EVIDENCE_DIRECTORY="$PWD/.scratch/issue-159/development"
# 默认查找当前 Compose 项目的 local-https-edge；非默认项目设置 COMPOSE_PROJECT_NAME。
# 也可指定已核实属于本次开发项目的容器 ID：SF_SECURITY_EDGE_CONTAINER。
bash scripts/verify-console-authentication-e2e.sh --development
```

此入口依次运行 Chromium、WebKit、Chrome。每个浏览器先对四域执行正常 TLS 导航；缺少浏览器、信任或服务记录 `blocked`，后续阶段记录 `not-run`。测试实际失败记录 `failed`。只有该环境全部渠道及阶段通过才返回 0。开发保留 Vite 模块与正式域名 WSS connected 证据；不清理开发数据库或创建测试 Tenant。

Fresh Compose 继续使用原入口。443 必须空闲；不能覆盖开发 Edge，先通过其受控生命周期释放端口，验收退出后再恢复开发入口。

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/four-domain-server.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/server.key
export SF_BRAND_EVIDENCE_DIRECTORY="$PWD/.scratch/issue-159/fresh-compose"
bash scripts/verify-console-authentication-e2e.sh --preflight
bash scripts/verify-console-authentication-e2e.sh
```

`--product` 仅复用已有构建、重跑产品及兼容门禁，不代表本次执行了 Maven/workspace；`SF_PRODUCT_CHANNEL` 仅可用于本地 `--product` 聚焦，不是完整矩阵证据。每个渠道使用独立浏览器上下文，Compose 数据卷在渠道之间删除并重新初始化。清理只使用本次随机项目名；清理失败使整轮失败。Remote 制品仍由唯一构建目录及 `remote-static.mjs` 交付；两个环境都从浏览器下载资源并对照冻结 SHA-256 清单。

## 证据与安全

- `development-matrix.json`：开发渠道、版本、预检、阶段结果与阻塞分类。
- `acceptance-run.json`：Fresh 执行的提交、工作区修改标志、范围与阶段状态；必须结合 scope 判断，不能把 `--product` 当完整验收。
- `static-remote-<channel>.json`：模块执行、CSS 生效、图片解码、资源请求及无凭据/CORS 元数据。
- `static-remote-policy-<channel>.json`：冻结制品 SHA-256、版本重复读取、真实 404、非法来源浏览器拒绝与对应 Edge 响应。
- `session-security-<channel>/browser-sessions.json`：双槽位流程、Cookie 属性与范围、32 个探针、拒绝后 Cookie 不变及两侧真实恢复、开发 HMR。

Chromium 系列使用 CDP ExtraInfo；WebKit/Firefox 使用公开 Playwright 网络 API，CORS 隐藏的预检/拒绝由同一真实 TLS Edge 的随机关联探针日志补充。Edge 不修改请求或放宽安全规则；日志不包含 Cookie/Token 值、密码或正文。服务端不变必须由每个探针后的双 Console 实际恢复共同证明，不能仅凭 fetch 抛错或 Cookie 字节未变。

受限 `.log` 文件可能包含原始测试诊断，不直接公开。CI 使用原有五浏览器工作流，四域都纳入证书 SAN 和 hosts；延续此前已批准的 Linux `saasforge.example.com` 对照根域及相同主机推导策略。工作流以 `always()` 上传白名单 JSON，排除原始日志、凭据与截图。远端验收需记录对应提交的 workflow URL 和 artifact，不能引用旧运行替代。

## 父规格验收映射

| #155 验收项 | 对应证据/检查 |
| --- | --- |
| 四域受信 HTTPS | 两套环境分别预检及真实页面；CI SAN/hosts 四域 |
| 开发 HMR、E2E 构建、同 Remote | 会话证据 hmr；静态构建入口；浏览器 SHA-256 对照 |
| 双 Console 登录/恢复/独立登出 | browser-sessions actions 与每次实际刷新 |
| Cookie 安全属性与 API 主机范围 | Set-Cookie 属性、实际 Console/Remote 请求无 API Cookie |
| API 精确 CORS，Remote 不获许可 | 两侧预检头集合、实际响应、Remote/非法/null 探针 |
| CSRF 与槽位拒绝、状态不变 | 32 个探针及拒绝后双侧恢复 |
| ES Module/CSS/图片实际执行呈现 | static-remote rendering 与真实资源请求 |
| Remote 无凭据及精确 CORS | 非敏感 Cookie 夹具、网络头、关联负向 Edge 记录 |
| 固定双版本与真实 404 | policy versions、artifactHashes、missing |
| 本地三浏览器与 CI 五浏览器 | 分环境矩阵、对应远端运行和上传 JSON |
| Fresh 状态隔离与限项目清理 | 原随机 Compose 项目/全新卷检查、reset/cleanup 结果 |
| 文档与 MVP 状态 | 本文；全部证据成立前保留 MVP 未勾选 |

## 本次完成记录

实现与运行状态分开记录。最终运行结果尚未齐备时，本任务及 MVP 四域条目保持未完成；不以单个浏览器通过或静态检查通过替代聚合验收。
