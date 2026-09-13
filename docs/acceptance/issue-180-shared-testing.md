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

`bash scripts/verify-console-authentication-e2e.sh --product` 已尝试，**预检受阻**：本次进程未设置 `SF_ACCEPTANCE_TLS_CERT` / `SF_ACCEPTANCE_TLS_KEY`，且 `127.0.0.1:443` 被现有服务占用。Chrome 153.0.8010.36 可启动，但未进入 TLS 导航与 Fresh Compose 阶段。记录：`/tmp/issue180-fresh.log`，预检证据目录 `sf-brand-evidence.R6pMel`。未创建验收项目，也未停止已有服务或删除已有数据卷。

因此，本次 Fresh 的 Platform 初始改密/登录/恢复/退出、Tenant Membership/Context、槽位与多标签竞争、双语故障表单、Locale/品牌及安全拒绝路径均为**未执行**，不能用已有测试源码或本机 Chromium 结果代替。需要开发者释放 443 并提供受信四域证书路径后复跑上述入口。

远端 CI 尚未执行；已接入的 `console-visual` job 尚无当前提交的远端运行结果。MVP 对应事项保持未勾选，Issue 保持 OPEN。

## 代码审查

- Standards：初次发现英文 Shell 测试未同步组件语言，已改为读取当前 Console Locale；复核剩余 0 项。
- Spec：初次发现恢复面板分支/文案及共享退出保护覆盖不足，已补齐并更新覆盖清单；复核剩余 0 项。
- 审查为源码核对；测试执行结果以上表及后续完整验收为准。
