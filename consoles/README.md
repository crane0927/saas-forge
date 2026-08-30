# 控制台边界

本目录承载 Platform Console、Tenant Console Shell、业务 Remote 与共享前端代码四个独立边界。当前已交付两个最终应用宿主、生成 API Client 和共享应用 Runtime；业务 Remote 尚未实现。

本目录是唯一的 pnpm workspace 根，固定使用 Node 24.14.1 与 pnpm 11.22.0。共享依赖版本由 `pnpm-workspace.yaml` 的默认 Catalog 集中管理，lockfile 记录解析后的精确版本。Platform Console 和 Tenant Console Shell 是彼此独立的 Vite + React 应用；本阶段不初始化 Module Federation，也不创建没有实际职责的共享包。

`shared/api-client` 是无状态 TypeScript REST Client。Maven/OpenAPI Generator 是唯一生成权威，生成物位于被 Git 忽略的 `.generated`；手写代码只允许从 `@saas-forge/api-client` 公开入口导入 API、模型和运行时类型，不得直接导入生成目录。它不实现认证、Cookie、CSRF 或 Token 存储。

`shared/app-runtime` 是依赖无关的 Runtime Config 与 Bootstrap 内核。它从同 Origin 的 `/runtime-config.json` 加载严格的 `schemaVersion`、`apiBaseUrl` 两字段契约，只接受绝对 HTTPS API Origin，并向应用暴露稳定的失败码和显式用户重试状态。它不依赖 React、路由、认证或生成 API Client 的请求实例。

## 环境准备与完整验证

从无生成 Client、无 `dist` 的干净状态执行：

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm run verify
```

`verify` 先通过根命令 `generate:api` 调用 Maven/OpenAPI Generator 正式生成 Client，再一次完成所有手写代码的严格 TypeScript、ESLint、Prettier 检查，执行 `app-runtime` 与两个应用的 Vitest/React Testing Library 测试，最后分别生产构建两个应用。生成代码参与严格类型检查，但不参与手写代码的 Lint 和格式检查。Maven `verify` 复用同一个 workspace 聚合门禁，不安装 Node/pnpm，也不会在缺失依赖时自动联网。

## 根命令

```bash
pnpm run dev:platform
pnpm run dev:tenant
pnpm run typecheck
pnpm run test
pnpm run build
pnpm run verify
```

`dev:platform`、`dev:tenant`、`build` 和 `verify` 都先生成正式 Client。`typecheck` 与 `test` 只聚合已准备好的 workspace 包；包内部命令只验证本包，不反向调用 Maven。两个应用也可在各自目录单独执行 `dev`、`typecheck`、`lint`、`format:check`、`test`、`build` 和 `verify`。

## 静态制品与部署配置

生产构建分别输出 `platform-console/dist` 和 `tenant-console-shell/dist`，两者是独立制品，必须发布到各自受控 Origin。每个制品内的 `/runtime-config.json` 都是故意非法的部署模板；部署流程必须将它原子替换为严格的两字段配置，例如：

```json
{
  "schemaVersion": 1,
  "apiBaseUrl": "https://api.example.test"
}
```

`apiBaseUrl` 必须是无凭据、路径、查询参数和 Fragment 的绝对 HTTPS Origin。不得通过 Runtime Config 改变应用身份、路由、菜单或授权行为。

## 证据边界

当前门禁只证明最终 Platform/Tenant 应用宿主、共享 Runtime、生成 Client 的稳定入口和两个静态生产构建存在；不证明登录、真实 API 调用、受控 TLS Origin、业务 Remote 或 Playwright 浏览器闭环。
