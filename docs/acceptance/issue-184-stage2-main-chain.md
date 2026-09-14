# Issue #184：中文身份与 Tenant 主链

关联 [Issue #184](https://github.com/crane0927/saas-forge/issues/184) 与父规格 [#183](https://github.com/crane0927/saas-forge/issues/183)。

## 运行入口

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/trusted-cert.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/trusted-key.pem
export SF_BRAND_EVIDENCE_DIRECTORY=/absolute/path/to/new-evidence-directory
bash scripts/verify-console-authentication-e2e.sh --stage2
```

入口复用既有 Fresh Compose 的随机项目、全新数据卷检查、受限凭据、基础引导、四域 TLS 就绪门禁和本轮清理。443 被占用时在构建和启动之前拒绝执行，由开发者释放入口；不停止或替换开发服务。执行完整 Maven/workspace 检查后构建本轮服务镜像；Tenant Console 挂载正式生产制品，退出 Remote/故障验收路由包装。

主链仅运行 Chrome、浏览器语言 zh-CN，无保存的语言偏好；不注入 localStorage。顺序为首次平台登录和改密 → max_users 与 Plan → 两个 Tenant、Subscription、同一 Tenant Administrator → 真实邮件设置密码 → 两个 Accessible Membership 的首次选择 → 切换与刷新 → Audit。所有业务创建和正常身份操作通过页面及原有共享 Client；没有 API 预建、写库种子或成功响应伪造。

Audit 观察只读本轮生产 Outbox 的事件引用与 Trace，再匹配 Audit Record 和消费记录。按操作时间、具体身份、资源、目标 Tenant/Membership 约束，逐事件最多等待 30 秒；必须已经发布并被消费，历史任意事件不能满足断言。

## 同轮复用与产物

`runStage2MainChain(consume)` 顺序完成主链后调用 `consume`，提供同轮 `runId`、浏览器页面、资源及内存凭据。后续安全/OAuth 切片应在此回调内执行，不得读取历史 JSON 预建前置或持久化凭据；回调失败使整轮失败，返回后销毁浏览器。独立 #184 入口不传回调即可验证本切片。

`acceptance-run.json` 记录编排阶段；`stage2-main-chain.json` 记录代码基线、工作区状态、运行标识、Chrome/JDK 版本、逐场景状态、非敏感资源引用、Audit 事件/Trace 关联和预期匿名拒绝。未执行阶段保持 `not-run`。匿名无会话刷新是明确的 401 场景，按页面、请求、场景、时间窗匹配；未知 HTTP/Console 错误、请求失败和未捕获异常阻断。主链不生成截图、录像、浏览器 trace、请求正文、Cookie、Token、密码或邮件链接证据。原始 Playwright 异常不传播，仅保留受控源码位置。

键盘登录、按钮提交、路由焦点、播报和安全存储沿用共享验收帮助函数；完整 Maven 的 Console 门禁保留国际化、无障碍与浏览器测试。

## 当前验证记录

- 基线：`f2883f5e59a7181a43da386c3ac968867dce8a00`；开始时仅有既有未跟踪 `.scratch/`，未纳入提交。
- 通过：Console 类型检查、本次脚本 ESLint/Prettier、Shell 语法检查；入口与诊断测试 20 项；Compose 布局校验覆盖 8 个独立应用、6 个验收组合以及正式 Tenant 制品挂载。
- 完整 `VITEST_MAX_WORKERS=2 ./mvnw --batch-mode --no-transfer-progress verify` 通过，用时 6 分 9 秒，包含后端、数据库集成、Console 类型/lint/格式/工作区测试/Chromium 与生产构建。日志 `/tmp/issue184-maven-verify.log`。后续只读版本记录与编排小修正另经 ESLint/Prettier、Shell 和入口测试复核。
- code-review 双轴复审通过：Standards 原 2 项（清理与未处理等待器拒绝）、Spec 原 1 项（Console 401 关联过宽）均已修复，无剩余阻断；审查不代替真实主链执行。
- 真实环境预检：Chrome `153.0.8010.37` 可启动；宿主 `127.0.0.1:443` 已有监听，Fresh 主链未执行。没有关闭开发入口，也没有将历史 #181 证据计入本轮。
- 新测试入口先因实现模块尚不存在而失败；真实边界的 green 尚待 Fresh 运行，不能将静态检查称为 TDD 主链通过。
- 远端 CI 和父规格聚合：未执行；Issue 与阶段清单不据此勾选或关闭。
