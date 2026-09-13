# 共享前端测试基线

本基线实现 [Issue #180](https://github.com/crane0927/saas-forge/issues/180)，范围与完成条件见[测试策略](13-testing-strategy.md#第-1-阶段共享前端测试基线)。组件展示册、共享公开接口和两个 Console 的真实产品路径分别承担各自边界的证据，不新建产品验收页面。以下清单描述覆盖位置；本次实际执行结果另见[验收记录](acceptance/issue-180-shared-testing.md)。

## 执行入口

```bash
# 公开行为、双语与类型检查
pnpm --dir consoles run typecheck
pnpm --dir consoles run test

# 本机 Chromium 的行为、键盘、axe 和布局检查
pnpm --dir consoles run test:browser:chromium

# 固定 Linux 环境的正式图片比较；缺失或不同基线均失败
pnpm --dir consoles run generate:api
pnpm --dir consoles run test:visual

# 仅生成待审阅候选图片，不覆盖仓库基线
bash scripts/verify-console-visual.sh --update

# 完整构建和 Chrome 真实服务验收，按顺序执行
./mvnw --batch-mode --no-transfer-progress verify
bash scripts/verify-console-authentication-e2e.sh --product
```

日常按[本地分层验证](local-verification.md)选择影响范围。`test:visual` 在临时副本安装依赖，不覆盖主工作区的 `node_modules`；JSON 报告、退出码和图片写入 `.scratch/issue-180-visual/run.*`。公开依赖缓存在 `.scratch/console-visual-store`，清理后仅影响下次安装速度。退出时仅移除本次容器和临时工作副本。

视觉环境固定为 Linux ARM64、Playwright 1.62.1 的 Noble 镜像摘要（脚本中登记），镜像自带 Node 24.18.1、Chromium 和字体；项目日常 Node 版本继续为 24.14.1。CI 使用 `ubuntu-24.04-arm` 承载同一镜像，属于 [GitHub 标准 Runner](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。这只固定截图环境，不新增产品浏览器支持。

默认本机 Vitest 浏览器入口不执行图片比较；CI 独立的 `console-visual` job 强制启用比较，并以失败退出码阻断该门禁。完整构建和 Chrome 产品 job 不重复比较图片。macOS 旧图片保留作历史参考，Linux 图片才是当前 CI 权威基线；不能拿两种系统的渲染结果直接比较。

## 视觉基线审阅

候选输出与正式比较输出均保留组件及消费者 JSON 报告，失败时保留 actual/diff 图片。`--update` 只产生候选，不能作为正式比较通过记录。评审候选的文字、布局、对比度、焦点与缺失内容后，仅把已审阅的 `*-chromium-linux.png` 按相对目录复制到对应 `__screenshots__` 目录，再运行无参数正式比较。不得复制失败附件、原始日志或其他临时文件作为基线。

已有展示册、品牌、栅格、分栏、Platform 登录、Tenant 列表与 Remote 消费截图继续执行。新增矩阵：

| 入口 | 状态 | 组合 |
| --- | --- | --- |
| `stable-states.browser.test.tsx` | 字段错误/汇总、表格加载/空态/失败、普通/可恢复危险/不可恢复危险/未保存弹窗、启动/配置失败/根错误 | zh-CN × en-US；浅色 × 深色；1280 × 390 CSS px，共 72 个状态画面 |
| `authentication-states.browser.test.tsx` | 共享 AuthenticationShell 冷恢复中、恢复失败，并通过显式重试回到匿名登录 | Platform × Tenant；双语 × 浅深色 × 1280/390，共 32 个状态画面 |
| 既有展示册与消费者 | 主题、品牌接受/回退、布局和真实应用消费 | 保留既有 1440/1280/768/390/360 视口；320 CSS px 验证阅读、键盘与可操作性 |

截图使用固定数据、UTC、减少动画和镜像内字体。无障碍与键盘断言不依赖快照开关；新矩阵每个画面均执行 axe。WCAG 2.2 AA 是工程基线，自动扫描不替代真实键盘操作，也不构成正式认证。

## 公共组件覆盖清单

路径以下列目录为基准：`consoles/shared/design-system/test`、`consoles/shared/design-system/browser-test` 及 `consoles/browser-test`。类型别名没有运行状态；Token、颜色计算、品牌解析属于纯数据/函数边界，不强造 UI Locale 参数。调用方提供的对象名、数据和自定义文案视为输入，不要求测试夹具替换每一条业务数据；组件自有消息必须验证两种语言。

| 公共入口 | 适用状态与行为 | 对应测试 |
| --- | --- | --- |
| DesignSystemProvider、useDesignSystemLocale | 语言、浅深色、系统主题与品牌 Token | theme-provider、localization、stable-states |
| ApplicationLoading、ConfigurationFailure、ApplicationFatalError | 加载、配置失败、显式重试、根错误与安全重载；双语 | bootstrap-status、stable-states、authentication-states |
| ApplicationShell、ApplicationIdentity、LoginLayout | 导航、当前页、品牌名称/素材、登录、失败、窄屏；双语消费 | application-shell、public-content、login-ui、design-system-consumers |
| PageLayout、PageTitle、ResponsiveGrid、SplitLayout | 默认/全宽、栅格、主辅栏、语义及顺序；双语 Provider | layout、showcase、design-system-consumers |
| Button | primary/secondary/text/danger，可用/禁用/提交中、默认加载标签、防重复触发 | public-content、page-states、stable-states、showcase |
| Link、DesignIcon | 链接目标、命名图标/装饰图标、公开页面结构 | page-states（双语）、showcase |
| ContentPanel、DescriptionList、StatusTag | 标题/描述/数据语义，success/warning/danger/neutral | public-content（双语）、tenant-ui |
| TextField、PasswordField、SelectField、CheckboxField | 值、受控改变、失焦校验、错误关联、选择/勾选与提交 | forms（双语）、stable-states、showcase |
| FormLayout、FormRow、FieldError、FormErrorSummary | 表单语义、字段错误、错误汇总、布局和首个问题焦点 | forms、layout（双语）、stable-states |
| useFormProblemFocus、useUnsavedChangesGuard | 首个问题、脏表单离开、继续编辑/放弃、提交中保护 | forms（双语）、showcase |
| ActionMenu | 打开、选择、危险项分隔、Esc、焦点恢复 | overlays（双语）、server-table.browser、showcase |
| StandardDialog | 打开/关闭、键盘与触发点焦点恢复 | overlays（双语）、stable-states |
| RecoverableDangerDialog | 对象/后果、默认取消、禁止 Enter 危险提交、取消/确认 | overlays（双语）、stable-states、server-table.browser |
| IrreversibleDangerDialog | 精确确认文字、未满足时禁用、重新打开重置、确认后删除对象的焦点接续 | overlays（双语）、stable-states、showcase |
| UnsavedChangesDialog | 安全默认、继续/放弃、嵌套层 Esc 和焦点 | overlays、forms（双语）、stable-states |
| SuccessFeedback、WarningFeedback、PersistentError | 自动关闭、稳定语义键计时、持续警告/错误、重试与关闭 | page-states（双语）、localization、showcase |
| InitialContentLoading、RefreshingContent | 首次加载、局部更新、旧内容保留、更新结束 | page-states、server-table（双语）、stable-states |
| EmptyDataState、FilteredEmptyState、LoadFailureState、NotFoundState | 空数据/筛选无结果/失败/404、不同恢复动作 | page-states、server-table（双语）、stable-states |
| ServerTable | 查询/重置、排序/分页、选择清理、加载/空态/错误/刷新、行操作 | server-table（双语）、localization、server-table.browser、showcase |
| RouteFocusAnnouncement | 路由标题焦点、实时通知 | route-accessibility、authentication-shell（双语） |
| resolveBrandProfile、颜色/品牌/Token 导出 | 完整接受、结构/颜色/素材失败整份回退、取消/迟到；无 Locale 业务输入 | resolved-brand、theme-provider、showcase、brand-application |

## 共享交互状态覆盖

| 边界 | 状态/迁移 | 证据入口 |
| --- | --- | --- |
| AuthenticationRuntime 公开方法 | anonymous、authenticated、passwordChangeRequired、contextSelectionRequired、logoutPending；登录、改密、恢复、选择、切换、Refresh、退出及失败/重试/迟到结果 | `app-runtime/test/authentication-runtime.test.ts` 在 zh-CN/en-US 浏览器语言环境执行；Runtime 本身不接收 Locale |
| Browser Session Slot 协调 | 同 Origin 恢复竞争、跨槽位隔离、代次更新、登出优先、通知迟到与取消 | `app-runtime/test/session-coordination.test.ts` 双语环境；既有 sessions 浏览器与 Fresh 产品套件 |
| AuthenticationShell | 冷恢复→匿名/失败；登录→认证/改密/选择/失败；受保护返回路径；退出未知→显式重试；路由及根错误 | `react-shell/test/authentication-shell.test.tsx` 双语；`authentication-states.browser.test.tsx` |
| ConsoleLocaleProvider/Selector | 初始化优先级、显式切换、持久化、标签同步、存储异常、进行中的交互保持 | `react-shell/test/console-locale.test.tsx`、认证 Shell 显式语言切换测试、showcase/消费者浏览器测试 |
| 公开 Tenant/Quota Definition/Plan/Subscription RecoveryPanel | 初始未读取、显式读取、失败→重试、空记录、COMMITTED/PROCESSING/UNKNOWN/NOT_COMMITTED、允许/禁止的原操作恢复、忙碌防重、重放失败/重试/处理中重读、游标前进/返回 | `react-shell/test/operation-recovery.test.tsx` 四个公共面板分别双语，经真实 Runtime/生成 Client 与 HTTP 响应夹具验证；不直接测试内部 Panel |
| 品牌应用 | 无 Context、权威 Context、合法 Profile、整份回退、Context 切换与迟到读取 | brand-application、showcase、design-system-consumers 与 Fresh 产品套件 |
| FormExitGuard | 脏表单→继续编辑/放弃后操作、会话退出前确认 | `react-shell/test/form-exit-guard.test.tsx` 双语通过公开 Shell 与 Provider 验证继续编辑、放弃后退出、保存/卸载后的直接退出；DS forms 另验证底层 guard |

## 真实产品路径

沿用 `console-authentication.test.mjs` 及其 Fresh Compose 入口：Platform 初始改密/登录/刷新/退出、Tenant Membership 选择/Context 切换/恢复失败重试、两个槽位隔离、多标签恢复/退出竞争、错误边界、Locale 状态保持与品牌原子应用。`console-problem-acceptance.mjs` 明确循环两个 Console × 双语 × 四类故障，断言不重放登录、不触发错误恢复、不泄漏原始响应、清空密码和窄屏可操作性。

真实成功路径使用正式 API 与服务；故障响应夹具仅用于负向注入。Fresh 套件包含其他已经交付的业务路径，保留它们的回归覆盖，不把其存在解释为本项新增业务范围。产品支持仍按 ADR 0046 使用桌面 Chrome 和 JDK 17。
