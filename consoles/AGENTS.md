# 前端 Agent 工作目录

本目录保留在 SaaS Forge 单仓库中，继承根目录的公共约束。前端任务以 `consoles` 为工作目录启动，使本目录的项目级配置生效；不修改用户的全局 Skill 或 MCP 配置。

## 重构基线

- 按 [ADR 0050](../docs/adr/0050-consoles-adopt-soybean-element-plus.md) 使用 [Soybean Admin Element Plus 官方仓库](https://github.com/soybeanjs/soybean-admin-element-plus)，技术栈为 Vue 3、Element Plus、Vue Router、Pinia、TypeScript 和 Vite。
- 上游源码固定提交及许可证见 [UPSTREAM.md](shared/admin/src/vendor/soybean/UPSTREAM.md)。沿用上游布局和组件范式，业务页面直接使用 Element Plus。已有 UI 偏好及完整迁移范围见 [实施计划](../docs/plans/console-soybean-element-plus-refactoring.md)。
- 两个 Console 共享基础能力与版本，分别保留入口和浏览器安全边界。纯 TypeScript `api-client` 与 `app-runtime` 复用；React 页面与 Hook 迁移为 Vue SFC 和 Composable。
- 会话凭据由 Runtime 私有持有。适配模板时保留项目认证、原操作恢复、品牌切换、脏表单退出、国际化与无障碍语义。
- 旧 UI、React Shell、Ant Design 工具及其专属脚本随消费者和必要验证迁移后清除。正式入口整体切换，最终交付不保留旧页面备用开关或双框架兼容层。

## 文档与工具

- 组件 API 从 [Element Plus 官方文档](https://element-plus.org/zh-CN/) 查询，以 [llms.txt](https://element-plus.org/llms.txt) 为文档索引；按需读取具体组件和版本说明。
- 设计参考采用 [Element Plus 设计原则](https://element-plus.org/zh-CN/guide/design.html) 和 [Soybean 官方文档](https://docs.soybeanjs.cn/zh/)，实际布局以锁定的上游源码为依据。原 Ant Design `design.md` 退出新页面设计依据。
- 在线文档会更新；当前组件版本见 `shared/admin/package.json` 和锁文件。涉及版本差异时核对安装包类型声明与固定提交，避免照搬在线文档中新版本 API。
- 在 `consoles` 执行 `pnpm install --frozen-lockfile`。新 Vue 模块的类型检查、lint、测试、构建和布局浏览器验证通过 `pnpm --filter @saas-forge/admin run <脚本>` 执行。
- Vue 模块使用其依赖支持的 ESLint 9；工作区根 lint 命令同时调用 Vue 模块检查。第三方上游源码保留原样，项目适配代码纳入检查。

## 验证与交付

- 底座夹具的构建、视觉与交互检查仅证明底座接入，不代表正式业务验收。
- 正式页面迁移需验证既有业务、安全、键盘、无障碍、国际化及 Remote 边界；完整清理目标和不可回归语义以实施计划为准。
- 本地应用生命周期遵循根目录约束：在应用目录执行 `pnpm run dev`，浏览器正式联调沿用受信 HTTPS、受控域名与 Gateway。
