# `@saas-forge/design-system`

SaaS Forge 浏览器界面的唯一公共 Design System 包。当前公共根入口提供：

- `DesignSystemProvider`：安装唯一 Ant Design Theme Provider、全局基础样式与语义 Token；
- `semanticTokens`：平台主色、系统字体、基础颜色与 `4px` 级间距；
- `ApplicationLoading`：应用首次启动和部署配置加载状态；
- `ConfigurationFailure`：持续配置失败状态与显式重试操作。
- `ActionMenu`：统一菜单触发、危险项分隔、Esc 关闭和焦点恢复；
- `StandardDialog`：普通弹窗与可选主操作；
- `UnsavedChangesDialog`：以“继续编辑”为安全默认值的未保存确认；
- `RecoverableDangerDialog`：明确对象与后果的可恢复危险确认；
- `IrreversibleDangerDialog`：必须精确输入对象名称或指定文字的不可恢复危险确认。

消费者只能从 `@saas-forge/design-system` 根入口导入，不得导入 `antd`、本包内部文件或额外全局样式。缺少公共能力时先扩展本包，再升级消费者；不复制等价组件作为临时实现。

本包可独立执行：

```bash
pnpm --filter @saas-forge/design-system run verify
```

私有组件展示入口可通过以下命令启动：

```bash
pnpm --filter @saas-forge/design-system run dev:showcase
```

后续页面状态、反馈、表单、表格、图标、深色主题、Tenant 品牌与完整展示矩阵由对应 Issue 分批交付；当前展示入口只覆盖已正式交付的启动与浮层能力。
