# Console Tenant A 方案验证记录

日期：2026-09-11。用户选择 A「分区列表」后实施；范围与取舍见 [ADR 0047](../adr/0047-console-visual-upgrade-preserves-shared-ui-boundaries.md)。规范仍由 [Design System](../25-design-system.md) 维护。

## 已实现

两个 Console 共用可折叠侧栏与紧凑导航抽屉，语言选择器在认证顶栏保持单实例。Platform Tenant 列表、创建、详情与创建恢复区使用共享组件；浅灰画布、独立查询与表格面板、状态标签、详情键值保持同一套浅色/深色语义。没有新增业务菜单、编辑、删除、批量操作或公开 UI 包。

已修复浏览器检查发现的抽屉反向 Tab 焦点逸出、关闭后的焦点恢复，以及选中导航和浅色警告标签的对比度。导航选中文字使用可读的正文色，品牌色仍用于选中背景和主操作。窄屏顶栏保留品牌 Logo。

## 自动化证据

以下命令在 `consoles` 执行，Design System 浏览器命令在其包目录执行。

| 检查                                                                 | 结果                                                               |
| -------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `pnpm exec tsc -p tsconfig.json` 与受影响包 typecheck                | 通过                                                               |
| 三包 lint：design-system、react-shell、platform-console              | 通过                                                               |
| 三包 unit tests                                                      | 73 + 33 + 10 通过                                                  |
| `pnpm run validate:i18n`                                             | 通过                                                               |
| `pnpm run build:workspace`                                           | 通过；全局 CSS 单份、消费者按需制品检查通过；已有大 chunk 提示保留 |
| Chromium 消费者浏览器（含 A 浅色/深色截图）                          | 14 通过                                                            |
| Chromium Design System 浏览器（含截图）                              | 19 通过                                                            |
| Chrome 消费者，`SF_BROWSER_CHANNEL=chrome SF_VISUAL_SNAPSHOTS=false` | 12 通过，2 项截图矩阵跳过                                          |
| Chrome Design System，同上环境变量                                   | 15 通过，4 项截图矩阵跳过                                          |

新增 `browser-test/tenant-ui.browser.test.tsx` 运行真实 Platform 组件与共享客户端，通过受控请求夹具验证名称筛选、详情、单一语言入口、单一 main、320px 抽屉焦点/退出及浅深色 axe 检查。夹具数据不是真实业务数据。截图已人工检查 A 浅深色、共享主题/语言矩阵、登录页与窄屏 Remote 的代表画面。

## 真实本地运行证据

使用开发者启动的服务和受信 HTTPS 入口 `https://platform.saasforge.test/tenants`，开发者完成登录。内置 Chromium 浏览器观察到：

- 初始页面进入 A 布局，列表读取失败；重试能够触发同一错误状态，查询区保留。
- 开发者提供 Tenant Access 日志：`list_platform_tenants(character varying, unknown, unknown, integer) does not exist`。
- 只读查询本地 `tenant_access_db`：Flyway 历史为 V1–V11，目标函数不存在；Flyway info 确认 V12–V14 Pending。
- 在 `/tmp/saas-ui-tenant-access-before-v12.dump` 创建权限受限的 pg_dump 备份，pg_restore --list 读取成功。通过既有 `tenant-access-migrate` 单次任务验证 14 项迁移并补齐 V12–V14；没有修改迁移文件、执行 repair、重启或替换 IDE 服务。
- 同一页面重试后读到 10 条既有 Tenant；名称筛选“本地开发 Tenant”返回一条，详情展示 ID、名称、状态、创建时间与未设置到期时间。
- 创建“UI A 验收 20260911”成功，返回详情，ID `01a090ac-12e1-7afb-b3ed-8a5713d74627`，状态 PENDING。此验收数据保留在本地库，没有初始化管理员。
- 服务端创建记录显示同一名称及“已提交”，并提供“查看 Tenant”操作。

## 验收边界

本次实测覆盖本地列表、筛选、详情、创建和恢复记录读取；未通过故障注入实测创建请求丢失后的恢复提交，相关既有单元测试通过。真实库条数未超过每页 50，跨页业务流程未实测。真实业务浏览器为内置 Chromium；桌面 Chrome 的组件/消费者测试通过，不将其写成 Chrome 真实后端全流程验收。未运行 Fresh Compose 或完整认证安全矩阵，未关闭对应 Issue。
