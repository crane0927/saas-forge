# Issue #125 Console 国际化验收证据

## 验收边界

本记录汇总父 PRD #117 与实现票 #118–#124 的直接证据，并记录 #125 的最终集成验证。它只覆盖当前已交付的 Platform Console、Tenant Console、共享组件与静态 Remote 消费夹具；不建设或验收 Manifest、Module Federation、真实 Remote 加载器、运行时语言包服务或后端本地化契约。

Fresh Compose 由 `scripts/verify-console-authentication-e2e.sh` 使用随机项目名、临时 Secret 和全新数据卷创建。每个产品浏览器渠道完成后均执行 `compose down --volumes --remove-orphans`，不读取或删除默认开发栈数据卷。浏览器以正常证书校验访问 `platform.saasforge.test`、`console.saasforge.test` 与 `api.saasforge.test` 的 HTTPS 443 产品拓扑，不使用 localhost 端口替代，也不设置 `ignoreHTTPSErrors`。

## 父 PRD 对应表

状态定义：`通过` 表示当前仓库已有直接自动化证据；`待 CI` 表示本地可用渠道已通过，但 #125 要求的 Firefox 或 Edge 当前提交证据仍须由 CI 产生；`失败` 与 `跳过` 必须单独列出，不能并入通过。

| #117 验收项                                                                        | 实现责任                     | 直接证据                                                                                                       | 当前状态 |
| ---------------------------------------------------------------------------------- | ---------------------------- | -------------------------------------------------------------------------------------------------------------- | -------- |
| 浏览器语言、手动偏好、区域回退与非法偏好                                           | #118、#119                   | `shared/i18n/test/index.test.ts`、`shared/react-shell/test/console-locale.test.tsx`                            | 通过     |
| 第三语言的注册、精确匹配、资源完整性与生产双语边界                                 | #124                         | `test/i18n-resources.test.mjs`、`shared/i18n/test/index.test.ts`、`shared/i18n/src/locale-registry.json`       | 通过     |
| 类型化消息、插值、复数、英文回退与安全恢复                                         | #118、#122、#124             | i18n、Design System、React Shell 单元测试及资源坏夹具                                                          | 通过     |
| 日期、时间点、数字、精确小数和金额                                                 | #122                         | `shared/i18n/test/index.test.ts`、`shared/design-system/browser-test/showcase.browser.test.tsx`                | 通过     |
| 公共组件、认证/恢复、Tenant 路径、导航、表单、反馈、错误与无障碍标签               | #120、#121、#122             | 两个 Console 包级测试、Design System 浏览器矩阵、Fresh Compose 产品测试                                        | 通过     |
| 两个 Console 首次渲染、`html.lang`、配置失败、登录/恢复与根故障                    | #118、#120                   | 两个入口测试、消费者浏览器测试、`integration-test/console-authentication.test.mjs`                             | 通过     |
| 切换时保留输入、弹窗、路由、焦点、提示、在途请求及 Runtime/Remote 身份             | #119、#120、#121、#122、#123 | React Shell 单元测试、Design System/Remote 浏览器测试、Fresh Compose 产品测试                                  | 通过     |
| 刷新、登出、换账号、Tenant 切换保持偏好并按 Origin 隔离                            | #119、#120、#121             | `integration-test/console-default-realm.test.mjs`、Fresh Compose 登录/刷新/切换/登出路径                       | 通过     |
| 同 Origin 标签页同步、竞争写入、迟到事件、重新激活、删除、异常值与浏览器语言变化   | #119                         | `shared/react-shell/test/console-locale.test.tsx`、`integration-test/console-default-realm.test.mjs`           | 通过     |
| 存储读写失败、首屏回退、合法键白名单与认证槽位隔离                                 | #119、#120                   | React Shell 存储失败测试、`expectSafeStorage` 产品审计、认证边界测试                                           | 通过     |
| 静态 Remote 初始/动态 Locale、状态保持与只读边界                                   | #123                         | `browser-test/design-system-consumers.browser.test.tsx`、Remote 单元/边界/构建测试                             | 通过     |
| 资源自动发现、键与参数、ICU、内容结构及发布拒绝门禁                                | #124                         | `scripts/validate-i18n-resources.mjs`、`test/i18n-resources.test.mjs`、`verify:workspace`                      | 通过     |
| 真实 TLS/Origin 与 Fresh Compose 英文核心路径、中文代表路径、API/刷新/console 证据 | #120、#121、#125             | `integration-test/console-authentication.test.mjs` 经统一 Fresh Compose 入口执行；每个渠道 17 项且无失败或跳过 | 待 CI    |
| 工作区、浏览器兼容、窄屏、键盘、焦点、无障碍与缺失项记账                           | #119–#125                    | `verify:workspace`、四个 `test:browser` 兼容入口、Fresh Compose 五渠道 CI                                      | 待 CI    |

## #125 本地集成结果

验证日期：2026-09-06（Asia/Shanghai），当前提交基线 `5b89cb4` 加本票工作区改动。

| 验证                                   | 结果       | 证据边界                                                                                                                      |
| -------------------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------------------- |
| 根 Maven 门禁                          | 通过       | 第一次聚合中的 `mvnw verify` 通过；此后只修改浏览器测试与本记录，未修改后端源码                                               |
| 当前 Console workspace 门禁            | 通过       | `verify:workspace` 通过类型、Lint、格式、单元/边界测试、Chromium 浏览器测试、资源自动发现及两个生产构建                       |
| WebKit Fresh Compose 产品路径          | 17/17 通过 | 独立 Fresh Compose、正常 TLS 校验、0 失败、0 跳过                                                                             |
| Chromium Fresh Compose 产品路径        | 17/17 通过 | 独立 Fresh Compose、正常 TLS 校验、0 失败、0 跳过                                                                             |
| Chrome Fresh Compose 产品路径          | 17/17 通过 | 独立 Fresh Compose、正常 TLS 校验、0 失败、0 跳过                                                                             |
| Chrome 工作区浏览器兼容门禁            | 通过       | Design System、消费者、原生标签页入口                                                                                         |
| WebKit 工作区浏览器兼容门禁            | 通过       | Design System 7 通过/3 个视觉快照跳过，消费者 7 通过/1 个视觉快照跳过，原生标签页 4/4 通过；聚焦 Locale 回归另连续 10/10 通过 |
| Firefox、Edge Fresh Compose 与兼容门禁 | 待 CI      | 本地入口明确不运行，不能由 Chromium/Chrome/WebKit 代替                                                                        |

修复后使用当前生产构建执行 `scripts/verify-console-authentication-e2e.sh --product`，三套 Fresh Compose、三个本地产品渠道及 Chrome/WebKit 兼容门禁在同一聚合命令中全部通过并返回 0。该命令按设计不重复 Maven/workspace；它们分别由上表两项直接验证。

产品测试直接操作登录、首次改密、会话恢复、Membership 选择、Tenant Context Switch、拒绝/重试、受保护导航、语言切换、刷新和登出，并核对对应 `/api/*` 响应。主产品用例从合法 `en-US` 偏好冷启动，英语完整覆盖 Platform 登录、首次改密、导航、登出及 Tenant 登录、Membership 选择、正常切换和失败恢复；失败状态中切换到中文，直接验证中文提示、请求不重放，再切回英文完成恢复。后续中文路径继续覆盖代表性登录、Membership、Tenant 切换、拒绝与恢复。Locale 切换前后还核对业务请求计数、路由、焦点、现有提示和刷新后偏好。Tenant 名称与输入保持原值。

浏览器诊断只记录状态、路径、布尔安全结论和允许的 Problem code。测试审计浏览器存储、原生同步消息以及 Gateway、IAM、Tenant Access、Entitlement、Audit、两个 Console 和 TLS 代理日志，拒绝 Token、密码、Cookie 值、Membership 候选或原始异常进入验收输出。

## 失败、跳过与剩余问题

- 第一次本地聚合的三个 Fresh Compose 产品渠道与 Chrome 兼容门禁均通过；WebKit 组件门禁在 Locale rerender 前没有等待 Portal 内受控输入状态提交，失败于确认输入值保持断言。本票将操作改为等待弹窗焦点就绪、真实键盘输入完整确认文本，并以确认按钮启用作为提交屏障。产品组件实现未改变。
- WebKit 完整兼容门禁修复后通过。非 Chromium 渠道按既有矩阵跳过视觉快照，相关交互、Locale、Remote、键盘、无障碍与原生标签页用例没有跳过；视觉基线由根门禁中的 Chromium 测试负责。
- 补齐英文 Tenant 路径时，两次聚焦 Chromium 运行分别暴露登录前焦点预期遗漏和不受支持的 Select `Home`/`End` 测试操作，均发生在业务登录提交前。最终方案改为从合法 `en-US` 偏好冷启动，并保留既有相对键盘选择 helper；最终 Chromium 聚焦产品用例及三渠道产品聚合均通过。
- 当前提交尚未产生 Firefox 与 Edge 的 CI 证据，因此 #125 和父 PRD #117 不能仅凭本地结果宣称全部验收完成。推送后应等待 `.github/workflows/console-authentication-e2e.yml` 的五浏览器 Fresh Compose 任务及 Verify 全部成功，再更新 GitHub 核对项。
- 静态 Remote 证据只证明宿主传入 Locale、动态更新与状态保持；真实 Remote 加载器仍属于后续阶段，不能由本记录替代。
