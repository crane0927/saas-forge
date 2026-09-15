# Console 共享基础能力

[English](README-en.md)

采用 Vue 3 与 Element Plus；Soybean 布局的锁定提交与许可证见 [UPSTREAM.md](src/vendor/soybean/UPSTREAM.md)。

`mountConsole` 建立固定认证意图的应用入口，复用 `app-runtime` 的会话状态与类型化 Client。共享界面负责登录、公司切换、密码设置、恢复、语言、品牌原子应用和脏表单退出保护。业务页面直接组合 Element Plus 组件。

认证与原操作恢复的消息资源分别位于 `src/messages/authentication` 和 `src/messages/recovery`。品牌解析保留受控路径、MIME、解码、对比度和迟到响应校验；失败时整体回退到平台品牌。

```bash
pnpm --filter @saas-forge/admin run typecheck
pnpm --filter @saas-forge/admin run test
pnpm --filter @saas-forge/admin run test:browser
```

正式业务路由回归：`node --test integration-test/console-vue-products.test.mjs`。模拟 HTTP 与布局夹具不替代受信 HTTPS、真实后端的业务验收。
