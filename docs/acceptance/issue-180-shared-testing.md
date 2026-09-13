# Issue #180：共享前端测试基线验收

- 规格：[Issue #180](https://github.com/crane0927/saas-forge/issues/180)。
- 实现起点：`b49abfee0f4e2620755ba6202a3b74e15d4e86fe`；本记录对应当前实现工作区。
- 覆盖登记与复现命令：[共享前端测试基线](../console-testing-baseline.md)。
- 日期：2026-09-13。本地执行、远端 CI 与 Fresh 产品验收分别记录；未执行不能视为通过。

## 组件、交互与视觉

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Runtime 认证与会话协调双语环境 | 通过，208/208 | 本机 `/tmp/issue180-runtime.log` |
| AuthenticationShell 双语及显式语言切换 | 通过，34/34 | `/tmp/issue180-review-tests.log` |
| 四个公开恢复面板双语 | 通过，64/64 | `/tmp/issue180-operation-final.log`；包含状态文案、失败/空态、允许/禁止恢复、忙碌防重、失败重试、处理中重读与游标分页 |
| 共享 Shell 会话退出表单保护 | 通过，8/8 | `/tmp/issue180-review-tests.log`；双语脏表单继续/放弃、清洁/保存/卸载后退出 |
| 新稳定画面浏览器矩阵 | 通过，72/72 | `/tmp/issue180-stable.log`；双语、浅深色、桌面/窄屏，每例 axe 与行为断言 |
| 认证恢复与失败消费者矩阵 | 通过，16/16 | `/tmp/issue180-auth-browser.log`；每例恢复中→失败→显式重试→匿名登录 |
| 首次 Linux 候选生成及审阅 | 通过，审阅 129 张 | `.scratch/issue-180-visual/run.tcWJ0N`；使用九张联系表逐项检查文字、布局、内容与焦点后复制 Linux PNG |
| Linux 正式比对 | 通过，94 个组件＋40 个消费者测试，0 跳过 | `.scratch/issue-180-visual/run.CG8apB/{components,consumers}.json`，`exit-code.txt=0`；无 `--update`，129 张权威基线 |
| 缺失基线负向验证 | 按预期失败 | `.scratch/issue-180-visual/run.AF6KOs`，`exit-code.txt=1`；未生成/自动接受缺失基线 |

首次红测暴露并修复了三个实现缺陷：启动 Spin 的可访问标签缺少合适角色、Skeleton 向辅助技术暴露空标题，以及危险/主按钮交互颜色对比度不足。修复复用现有语义颜色，后续本机与固定 Linux axe 检查通过。自动扫描与键盘检查是 WCAG 2.2 AA 工程证据，不等于正式无障碍认证。

候选生成曾因容器 Corepack 下载连接重置失败，重试成功；该失败未计作通过。普通浏览器入口默认不比较平台不同的 PNG，独立 Linux 入口和 CI job 强制启用视觉比较。历史 macOS 图片保留，更新基线仍须人工审阅。

## 完整构建与 Fresh Compose

`./mvnw --batch-mode --no-transfer-progress verify` 通过，耗时 8 分 14 秒，日志 `/tmp/issue180-maven.log`。前端类型、lint、格式检查、550 个工作区单测、边界检查和制品构建通过；本机组件浏览器 90 通过/4 截图专用跳过，消费者浏览器 38 通过/2 截图专用跳过。六个截图专用用例已由上述 Linux 正式视觉门禁执行通过。

443 释放后复用现有 `deploy/compose/.secrets/local-https-development/server.pem` / `server.key`，四域 DNS、正常 TLS 校验与 Chrome 153.0.8010.36 导航预检通过。旧 `local-console-tls.pem` 缺少 Remote SAN，未用于正式验收；没有忽略证书错误或修改信任边界。

执行命令（只传文件路径，不输出密钥）：

```bash
SF_ACCEPTANCE_TLS_CERT="$PWD/deploy/compose/.secrets/local-https-development/server.pem" \
SF_ACCEPTANCE_TLS_KEY="$PWD/deploy/compose/.secrets/local-https-development/server.key" \
bash scripts/verify-console-authentication-e2e.sh --product
```

| 轮次 | 结果 | 记录 |
| --- | --- | --- |
| 初始预检 | 受阻，尚未创建环境 | TLS 路径未设置、443 被占用；`/tmp/issue180-fresh.log` |
| 首次真实 Fresh | 失败，38 通过/2 失败，0 跳过 | `/tmp/issue180-fresh-retry.log`；`sf-brand-evidence.aqGpiy`；通知 SMTP 恢复子测试在重新启动 Mailpit 后收到 503，预期 204，父测试随之失败 |
| 未改代码的独立 Fresh 复跑 | **通过，40/40，0 失败/跳过** | `/tmp/issue180-fresh-confirm.log`；`sf-brand-evidence.9ULzQI/acceptance-run.json` 的 `status=passed`、`commit=6cd6d83a716758ad754959c45bdb1329b2a52fae`、`dirty=false` |
| 重置数据卷后的 Chrome 浏览器门禁 | **通过** | 同一最终记录中 `compose-reset`、`console-browser-chrome` 均 passed，整个入口退出码 0 |
| 专属项目清理 | **通过** | 两轮项目的 Docker label 查询均无残留容器/卷：`saas-forge-console-1789310078-7455-ce5698`、`saas-forge-console-1789310444-9329-38a5bd`；未接管日常服务 |

最终运行覆盖 Platform 初始改密/登录/恢复/退出、Tenant Membership/Context、槽位与多标签竞争、中英文故障表单、Locale/品牌与安全拒绝路径。此命令复用前一节构建工件，没有重复执行 Maven/workspace 门禁。

首次 SMTP 恢复 503 在未修改代码的复跑中未复现，根因尚未确定。本记录保留该间歇失败，不以重试通过证明其稳定性已解决；本轮没有为获得通过而跳过测试、延长超时或放宽断言。完整受限诊断保留在 `sf-console-e2e-diagnostics.7TR06F`，不得直接上传原始日志。

远端 CI 尚未执行；已接入的 `console-visual` job 尚无当前提交的远端运行结果。MVP 对应事项保持未勾选，Issue 保持 OPEN。

## 代码审查

- Standards：初次发现英文 Shell 测试未同步组件语言，已改为读取当前 Console Locale；复核剩余 0 项。
- Spec：初次发现恢复面板分支/文案及共享退出保护覆盖不足，已补齐并更新覆盖清单；复核剩余 0 项。
- 审查为源码核对；测试执行结果以上表及后续完整验收为准。
