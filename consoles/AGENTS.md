# 前端 Agent 工作目录

本目录保留在 SaaS Forge 单仓库中，继承根目录的公共约束。前端任务以 `consoles` 为工作目录启动，使本目录的项目级配置生效；不修改用户的全局 Skill 或 MCP 配置。

## 重构基线

- 按 [ADR 0050](../docs/adr/0050-consoles-adopt-soybean-element-plus.md) 使用 [Soybean Admin Element Plus 官方仓库](https://github.com/soybeanjs/soybean-admin-element-plus)，技术栈为 Vue 3、Element Plus、Vue Router、Pinia、TypeScript 和 Vite。
- 上游源码固定提交及许可证见 [UPSTREAM.md](shared/admin/src/vendor/soybean/UPSTREAM.md)。沿用上游布局和组件范式，业务页面直接使用 Element Plus。已有 UI 偏好及完整迁移范围见 [实施计划](../docs/plans/console-soybean-element-plus-refactoring.md)。
- [ADR 0051](../docs/adr/0051-consoles-use-complete-soybean-applications.md) 已确认最终形态是直接基于完整官方应用开发（含登录页、布局、主题与导航），由 Issue #199 追踪。该迁移**尚未实施**：`shared/admin` 与 `shared/i18n` 仍然存在，tenant-console-shell 仍通过 `mountConsole` 挂载自建 `Workspace.vue`。改动这两个包时按"将被迁出"对待，不要在其上继续加深耦合。
- 两个 Console 共享基础能力与版本，分别保留入口和浏览器安全边界。纯 TypeScript `api-client` 与 `app-runtime` 复用；React 页面与 Hook 迁移为 Vue SFC 和 Composable。
- 会话凭据由 Runtime 私有持有。适配模板时保留项目认证、原操作恢复、品牌切换、脏表单退出、国际化与无障碍语义。
- 正式入口已使用 Vue；不得重新引入旧 React 页面、旧 UI 包、Ant Design 工具或双框架兼容层。

## 文档与工具

- 组件 API 从 [Element Plus 官方文档](https://element-plus.org/zh-CN/) 查询，以 [llms.txt](https://element-plus.org/llms.txt) 为文档索引；按需读取具体组件和版本说明。
- 设计参考采用 [Element Plus 设计原则](https://element-plus.org/zh-CN/guide/design.html) 和 [Soybean 官方文档](https://docs.soybeanjs.cn/zh/)，实际布局以锁定的上游源码为依据。本项目的 [design.md](design.md) 和 [llms.txt](llms.txt) 作为本地入口。
- 在线文档会更新；当前组件版本见 `shared/admin/package.json` 和锁文件。涉及版本差异时核对安装包类型声明与固定提交，避免照搬在线文档中新版本 API。
- 在 `consoles` 执行 `pnpm install --frozen-lockfile`。验证命令分三层，按改动范围选择，不要把某一层的通过当成更高层通过：
  - **工作区级**（在 `consoles` 执行，递归或聚合覆盖所有具备该脚本的包）：`pnpm run typecheck`、`pnpm run lint`、`pnpm run format:check`、`pnpm run test`、`pnpm run test:browser:chromium`。完整门禁是 `pnpm run verify`（= `generate:api` + `typecheck` + `lint` + `format:check` + `test` + `test:browser:chromium` + `build:workspace`）。
  - **单包级**：7 个 `@saas-forge/*` 工作区包（`admin`、`admin-consumer-fixture`、`api-client`、`app-runtime`、`i18n`、`platform-console`、`tenant-console-shell`）都各有自己的 `verify`。其中 `@saas-forge/admin` 的 `verify` 含 `test:browser`，`@saas-forge/api-client` 的 `verify` 只含 `typecheck`。
  - **仅工作区根具备**：`validate:i18n`、`build:workspace`、`generate:api`、`test:visual`。
  - 用 `pnpm --filter <包> run <脚本>` 时注意：所选包没有该脚本时 pnpm 会**静默跳过并返回退出码 0**。判断"某包的检查是否真的跑过"必须看输出，不能只看退出码。
- 工作区统一使用 Vue 插件支持的 ESLint 9，根 lint 命令同时检查纯 TypeScript 与 Vue 模块。第三方上游源码保留原样，项目适配代码纳入检查。

## 验证与交付

- 底座夹具的构建、视觉与交互检查仅证明底座接入，不代表正式业务验收。
- 正式页面迁移需验证既有业务、安全、键盘、无障碍、国际化及 Remote 边界；完整清理目标和不可回归语义以实施计划为准。
- 本地应用生命周期遵循根目录约束：在应用目录执行 `pnpm run dev`，浏览器正式联调沿用受信 HTTPS、受控域名与 Gateway。
