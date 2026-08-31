# Design System 消费与浏览器验证记录

**状态：Issue #102 的仓库实现与本地 Chromium 证据已建立；Chrome、Edge、Firefox 与 WebKit 由 CI 矩阵在每次 push/PR 重放。**

## 1. 验证边界

三个受控消费者为 Platform Console、Tenant Console Shell 和 `design-system-consumer-fixture`。三者只声明 `@saas-forge/design-system: workspace:*`，当前都解析到正式包版本 `0.1.0`。

最小 Remote 的 `src/remote.tsx` 不安装 `DesignSystemProvider`、不导入 CSS，也不包含路由、Runtime Config、产品领域页面或 Manifest 生命周期。验收宿主 `host/main.tsx` 安装唯一 Provider 后挂载 Remote，用于证明 Remote 继承 Shell 当前主题。

`pnpm run test:boundaries` 主动拒绝：

- 直接导入或声明 `antd`；
- 导入 `@saas-forge/design-system/*` 未公开内部入口；
- 导入消费者全局 CSS，或通过 `.ant-*`、公共 `.sf-*` 内部选择器覆盖组件；
- 以公共组件名称重复实现已有能力；
- Remote 安装 Provider，或任一 Shell 安装零个/多个 Provider；
- 三个消费者未使用同一工作区 Design System 版本。

缺少公共能力时的唯一贡献路径记录在 Design System README：先扩展公共包、补齐公共导出和验证，再由三个消费者统一升级与重建，不允许复制后治理的临时例外。

## 2. 可复现命令

```bash
cd consoles
pnpm run test:boundaries
pnpm run test:browser:chromium
pnpm run build:workspace
```

跨浏览器入口：

```bash
pnpm run test:browser:chrome
pnpm run test:browser:edge
pnpm run test:browser:firefox
pnpm run test:browser:webkit
```

CI 使用真实 Chrome、Microsoft Edge、Firefox 和 Playwright WebKit。WebKit 是现代 Safari 引擎的可复现兼容约定，不等同于已在原生 Safari 应用中执行；原生 Safari 无法在当前 Linux CI 执行，因此没有被静默记为通过。

## 3. 浏览器证据

本地真实 Chromium 验证覆盖：

- Platform Console 与 Tenant Console Shell 从共享启动状态进入路由，页面标题获得焦点；每个页面 DOM 只有一个 `.sf-design-system-root`。
- Design System 展示入口覆盖状态反馈、完整表单、服务端表格、危险确认、Enter/Space/Esc、菜单/弹窗关闭后的焦点恢复、axe 与减少动画。
- Remote 继承 Shell 的 `data-color-scheme` 与 `--sf-color-primary`，完成共享输入、按钮和成功反馈流程；桌面与 `390 × 844` 均非空、无框架错误层、无相关 console error/warn，窄屏 `scrollWidth = clientWidth = 390`。
- 展示矩阵保留 `1440px`、`1280px`、`768px`、`390px`、`360px` 五组稳定截图；Remote 另保留 `1280px` 与 `390px` 截图基线。

## 4. 版本、唯一入口与构建体积

`pnpm run build:workspace` 在三个消费者构建后执行制品门禁：每个制品必须只有一个 CSS 文件，且三个 CSS 内容的 SHA-256 必须完全相同。静态门禁与真实浏览器同时证明每个 Shell 只安装一个 Theme Provider。制品门禁还检查表格选择、危险确认和筛选空态的稳定实现标记没有进入三个代表性首屏；这些未使用模块一旦被错误打包，构建会明确失败。

2026-08-31 本地生产构建记录：

| 制品                            |  原始字节 | gzip 字节 |
| ------------------------------- | --------: | --------: |
| Design System 正式包            | 1,717,973 |   447,975 |
| Platform Console 代表性首屏     |   408,711 |   135,901 |
| Tenant Console Shell 代表性首屏 |   408,489 |   135,879 |
| Remote 消费夹具代表性首屏       |   456,914 |   148,888 |

正式包 gzip 大于选型原型中 Ant Design 候选约 `233.19 kB`，因为正式包已包含完整公共主题、反馈、页面状态、浮层、表单和服务端表格能力，而原型只覆盖候选场景。代表性首屏 gzip 低于该原型参考值，并由上述稳定标记门禁证明 Vite/Rolldown 按实际根入口引用进行 tree-shaking，没有把未使用的表格、危险确认和筛选空态实现带入首屏。本项目不据此臆造固定体积预算；后续公共能力变更继续记录实际差异并解释原因。
