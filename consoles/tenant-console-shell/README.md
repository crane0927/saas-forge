# Tenant Console Shell

Tenant 管理员使用的最终 Shell 宿主，是独立的私有 Vite + React workspace 应用包。

应用启动时先通过 `@saas-forge/app-runtime` 从同 Origin 加载 `/runtime-config.json`；配置未通过严格校验时保持失败关闭，只允许用户主动重试。生产构建内置的配置文件是故意非法的部署模板，部署流程必须原子替换它。`pnpm run dev` 由 Vite 开发服务器提供受控的 `https://api.saasforge.test` 配置，该值不会进入生产 Bundle。

配置就绪后，宿主通过共享 `@saas-forge/react-shell` 建立固定 `TENANT` 认证路径。冷启动恢复、登录、Accessible Membership 选择和退出均由共享 Runtime 驱动；访问令牌与候选列表只保存在当前标签页内存中。当前受保护路由只包含 Tenant 工作台，不包含 Manifest、Module Federation、动态 Remote 路由或产品业务 API 调用。

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

包级门禁证明 Tenant 宿主接线、认证集成和静态生产构建成立；真实浏览器交互与消费者边界由 Console workspace 根目录的浏览器和边界门禁覆盖。
