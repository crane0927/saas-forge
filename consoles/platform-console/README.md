# Platform Console

SaaS 产品提供方的平台管理控制台，是独立的私有 Vite + React workspace 应用包。

应用启动时先通过 `@saas-forge/app-runtime` 从同 Origin 加载 `/runtime-config.json`；配置未通过严格校验时保持失败关闭，只允许用户主动重试。生产构建内置的配置文件是故意非法的部署模板，部署流程必须原子替换它。`pnpm run dev` 由 Vite 开发服务器提供受控的 `https://api.saasforge.test` 配置，该值不会进入生产 Bundle。

当前路由只建立永久 Platform 应用根、未来公共/受保护区域的出口和诚实的 `404`，不包含登录、Dashboard、业务路由、Tenant 路由或产品 API 调用。

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

这些门禁证明应用接线和静态生产构建成立，不构成真实浏览器、受控 TLS Origin 或 Stage 1 完成证据。
