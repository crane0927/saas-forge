# `@saas-forge/design-system`

SaaS Forge 浏览器界面的唯一公共 Design System 包。当前公共根入口提供：

- `DesignSystemProvider`：安装唯一 Ant Design Theme Provider，默认跟随系统浅色/深色主题，并应用受控 Locale 与 Tenant 品牌；
- `semanticTokens`：唯一平台主色、浅色/深色表面、固定状态色、系统字体与 `4px` 级间距；
- `resolveTenantBrandProfile`：同时解析浅色与深色品牌颜色和前景色，任一输入不合法时原子拒绝；
- `RouteFocusAnnouncement`：路由切换后聚焦新页面主标题并通知读屏软件；
- `ApplicationLoading`：应用首次启动和部署配置加载状态；
- `ConfigurationFailure`：持续配置失败状态与显式重试操作。
- `PageLayout`、`PageTitle`、`Button`、`Link` 与 `DesignIcon`：统一页面结构、基础操作和首批图标；
- `SuccessFeedback`、`WarningFeedback` 与 `PersistentError`：短暂成功、持续警告与可恢复错误反馈；
- `InitialContentLoading` 与 `RefreshingContent`：区分首次内容加载和保留旧内容的局部更新，按钮通过 `loading` 表示提交中；
- `EmptyDataState`、`FilteredEmptyState`、`LoadFailureState` 与 `NotFoundState`：提供不同说明和至多一个恢复动作的页面状态；
- `ActionMenu`：统一菜单触发、危险项分隔、Esc 关闭和焦点恢复；
- `StandardDialog`：普通弹窗与可选主操作；
- `UnsavedChangesDialog`：以“继续编辑”为安全默认值的未保存确认；
- `RecoverableDangerDialog`：明确对象与后果的可恢复危险确认；
- `IrreversibleDangerDialog`：必须精确输入对象名称或指定文字的不可恢复危险确认。
- `FormLayout`、`FormRow`、`TextField`、`PasswordField`、`SelectField` 与 `CheckboxField`：统一单列表单、受控双字段行和标签上置的基础输入；
- `FieldError` 与 `FormErrorSummary`：关联字段错误、持续表单错误和可聚焦错误汇总；
- `useFormProblemFocus` 与 `useUnsavedChangesGuard`：首个问题聚焦，以及关闭、返回、页内切换和浏览器离站的未保存保护。
- `ServerTable`：提供服务端分页、单列排序、当前页逐行选择、固定操作列与危险菜单边界。

消费者只能从 `@saas-forge/design-system` 根入口导入，不得导入 `antd`、本包内部文件或额外全局样式。缺少公共能力时先扩展本包，再升级消费者；不复制等价组件作为临时实现。

## 缺少公共能力时的贡献路径

1. 先用实际 Console 或 Remote 场景说明缺少的无领域含义能力、交互状态和无障碍要求，不在消费者中建立临时公共组件。
2. 在本包中实现最小公共 API，并从 `src/index.ts` 根入口导出；同步补充单元测试、展示册状态和需要的真实浏览器键盘/焦点验证。
3. 运行本包 `verify` 与工作区边界、浏览器和制品门禁。公共 API 与验证通过后，三个消费者再统一升级到同一 Design System 版本并重建。

消费者可以使用 `.module.css` 排列领域内容，但不得导入全局 CSS，不得选择 `.ant-*` 或 `.sf-*` 内部类改变公共组件外观和交互。边界门禁不接受“先复制、后治理”的例外；确有领域专属组件时必须使用领域名称，且不能重复已有公共组件职责。

本包可独立执行：

```bash
pnpm --filter @saas-forge/design-system run verify
```

其中浏览器验证通过真实 Chromium 执行 axe 无障碍检查、键盘流程、减少动画和 `1440px`、`1280px`、`768px`、`390px`、`360px` 稳定状态快照。工作区另提供 Chrome、Edge、Firefox 与 WebKit（Safari 引擎约定）的 CI 入口；视觉差异只能在评审后通过 `--update` 更新基线。

私有组件展示入口可通过以下命令启动：

```bash
pnpm --filter @saas-forge/design-system run dev:showcase
```

当前展示入口覆盖启动、页面状态、反馈、图标、浮层、完整表单、服务端表格，以及中英文、浅色/深色、平台/Tenant 品牌与关键稳定状态矩阵。
