# 控制台边界

本目录预留四个独立前端边界：Platform Console、Tenant Console Shell、业务 Remote 与共享前端代码。

本目录是唯一的 pnpm workspace 根，固定使用 Node 24.14.1 与 pnpm 11.22.0。共享依赖版本由 `pnpm-workspace.yaml` 的默认 Catalog 集中管理，lockfile 记录解析后的精确版本。本阶段不初始化 React、Module Federation 或任一 Console 应用，也不创建没有实际职责的共享包。

`shared/api-client` 是无状态 TypeScript REST Client。Maven/OpenAPI Generator 是唯一生成权威，生成物位于被 Git 忽略的 `.generated`；手写代码只允许从 `@saas-forge/api-client` 公开入口导入 API、模型和运行时类型，不得直接导入生成目录。它不实现认证、Cookie、CSRF 或 Token 存储。

依赖安装与验证顺序：

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm run verify
```

`verify` 先通过根命令 `generate:api` 调用 Maven 正式生成 Client，再执行严格 TypeScript、ESLint 和 Prettier 检查。包内部命令只负责本包验证，不反向调用 Maven。生成代码参与严格类型检查，但不参与手写代码的 Lint 和格式检查。
