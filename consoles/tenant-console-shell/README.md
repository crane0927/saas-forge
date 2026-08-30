# Tenant Console Shell

Tenant 管理员使用的最终 Shell 宿主，是独立的私有 Vite + React workspace 应用包。

应用启动时先通过 `@saas-forge/app-runtime` 从同 Origin 加载 `/runtime-config.json`；配置未通过严格校验时保持失败关闭，只允许用户主动重试。生产构建内置的配置文件是故意非法的部署模板，部署流程必须原子替换它。`pnpm run dev` 由 Vite 开发服务器提供受控的 `https://api.saasforge.test` 配置，该值不会进入生产 Bundle。

当前路由只建立永久 Tenant Shell 根、本地路由挂载边界和诚实的 `404`，不包含 Manifest、Module Federation、动态 Remote 路由、登录、Dashboard、业务路由或产品 API 调用。

包级命令：

```bash
pnpm run dev
pnpm run typecheck
pnpm run lint
pnpm run format:check
pnpm run test
pnpm run build
pnpm run verify
```

这些门禁证明应用接线和静态生产构建成立，不构成 Remote、真实浏览器、受控 TLS Origin 或 Stage 1 完成证据。
