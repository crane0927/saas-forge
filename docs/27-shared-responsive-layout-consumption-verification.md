# 共享响应式布局消费与浏览器验证记录

**状态：Issue #106 的代表性 Remote 消费、静态边界门禁、Chromium/Chrome 行为与 Chromium 视觉证据已建立；Edge、Firefox 与 WebKit 的本机运行仍有环境或既有焦点门禁阻塞，两个 Console 尚无最终产品业务路由消费。**

## 1. 验证边界

`design-system-consumer-fixture` 继续只是代表性 Remote 消费夹具，不是产品领域页面。Remote 从 `@saas-forge/design-system` 根入口实际渲染全宽 `PageLayout`、`content` 与 `compact-statistics` 两种 `ResponsiveGrid` 意图，以及具有可访问名称的 `SplitLayout`。它不安装 `DesignSystemProvider`、不导入全局 CSS、不依赖 `antd`，也不覆盖公共布局的内部选择器、断点或尺寸。

验收宿主安装唯一 `DesignSystemProvider` 和 Design System 根入口附带的唯一全局样式。Platform Console、Tenant Console Shell 与 Remote 都声明 `@saas-forge/design-system: workspace:*`，解析到同一正式包版本 `0.1.0`。生产制品门禁要求三个消费者各自只有一个 CSS 文件，且三个文件的内容哈希完全一致。

本票不修改两个 Console 的产品路由。Remote 夹具只能证明公共布局能力与共享消费边界已经交付，不能证明 Platform Console 和 Tenant Console Shell 已在最终产品业务路由中消费这些布局。因此 [MVP 开发计划](16-mvp-development-plan.md)中的“响应式栅格和标准分栏布局”继续保持未完成，父 Issue #103 也不能据此关闭。

## 2. 静态边界失败用例

`pnpm run test:boundaries` 对下列违规提供明确失败结果：

- 从 `antd` 或 `antd/es/grid` 直接导入底层组件；
- 从 `@saas-forge/design-system/*` 导入内部入口；
- 导入消费者全局 CSS；
- 覆盖 `.ant-*`、`.sf-page-*`、`.sf-responsive-grid*` 或 `.sf-split-layout*` 内部选择器；
- 以 `PageLayout`、`ResponsiveGrid` 或 `SplitLayout` 名称重复实现已有公共布局；
- Remote 安装 Provider，任一 Shell 未安装或重复安装 Provider；
- 任一消费者脱离 `workspace:*`，或三个消费者解析到不同 Design System 版本。

## 3. 浏览器行为矩阵

代表性 Remote 的自动浏览器验证固定以下可用宽度：

| 可用宽度 | 普通内容列数 | 紧凑统计列数 | 主辅布局 |
| -------- | -----------: | -----------: | -------- |
| 1440px   |            3 |            4 | 左右分栏 |
| 1280px   |            3 |            4 | 左右分栏 |
| 768px    |            2 |            3 | 上下堆叠 |
| 390px    |            1 |            1 | 上下堆叠 |
| 360px    |            1 |            1 | 上下堆叠 |
| 320px    |            1 |            1 | 上下堆叠 |

测试还在 `1440px` 浏览器窗口内把 Remote 内容容器限制为 `40rem`，直接得到普通内容两列、紧凑统计三列和主辅上下堆叠，证明布局依据组件实际空间，而不是整个窗口宽度。

浏览器断言覆盖页面标题身份、非空主体、两种栅格的普通内容语义、命名辅助地标、主内容先于辅助栏的 DOM 与 Tab 顺序、可见焦点、辅助栏不隐藏、页面无横向溢出、无 Vite 错误层、无未处理运行时错误及无相关控制台错误或警告。桌面与窄屏稳定视觉基线经过重新生成和人工检查；窄屏基线使用足够页面高度展示全部统计项、主内容和辅助栏，不以截断截图代替布局证据。

## 4. 可复现验证

```bash
cd consoles
pnpm run test:boundaries
pnpm run test:browser:compatibility
pnpm run verify:workspace
```

跨浏览器入口覆盖真实 Chrome、Microsoft Edge、Firefox 与 Playwright WebKit。WebKit 是现代 Safari 引擎的项目约定入口，不等同于原生 Safari 应用验证。

工作区聚合验证继续覆盖 Design System、Platform Console、Tenant Console Shell、Remote 消费夹具的类型检查、格式、Lint、单元测试、Chromium 浏览器测试与代表性生产构建。既有 Console 启动状态、路由标题焦点，以及 Design System 表单、表格、危险确认和焦点恢复行为仍由原有测试集回归。

## 5. 2026-08-31 本机结果与边界

- `pnpm run test:boundaries` 通过，6 个边界测试全部成功。
- Remote Chromium 行为与更新后的桌面、390px 视觉基线通过，5 个消费者浏览器测试全部成功；应用内 Browser 另行确认 1280px 与 390px 页面身份、完整 DOM、几何顺序、提交反馈和控制台健康。
- `SF_VISUAL_SNAPSHOTS=false pnpm run verify:workspace` 通过，包含全部类型检查、Lint、格式、83 个单元测试、Chrome 之外的默认 Chromium 行为测试、三个消费者生产构建和制品哈希门禁。三个消费者构建产物继续只有一个且内容完全相同的 CSS 入口。
- 真实 Chrome 兼容入口通过：Design System 6 个行为测试和消费者 4 个行为测试成功，视觉用例按跨浏览器约定跳过。
- 标准 `pnpm run verify:workspace` 的功能测试均通过，但两个未被本票修改的 Design System 既有 Chromium 快照出现约 1% 的文字抗锯齿差异；本票没有更新这些无关基线。
- Microsoft Edge 安装需要 macOS 管理员密码，当前非交互式环境未能完成安装，因此 Edge 入口未执行。
- Playwright Firefox 已下载，但 macOS 拒绝其 headless 插件子进程，浏览器会话在测试开始前超时，因此没有测试结果。
- Playwright WebKit 可以执行；布局、语义和溢出用例通过，但 macOS WebKit 下既有 `userEvent.tab()` 不聚焦按钮，Design System 有 3 个焦点用例、消费者有 2 个焦点用例失败。该差异同时影响本票之前的既有焦点测试，未在 #106 中放宽断言或改写公共交互。

因此，本机证据足以确认公共布局、Remote 共享消费、Chrome/Chromium 行为和生产制品边界，但不足以声称四浏览器门禁全部通过，也不足以关闭 #106。Edge、Firefox 与 WebKit 需要在项目 CI 的受控运行环境重放并取得直接结果。
