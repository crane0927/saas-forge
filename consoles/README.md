# 控制台边界

本目录预留四个独立前端边界：Platform Console、Tenant Console Shell、业务 Remote 与共享前端代码。

本目录是唯一的 pnpm workspace 根；本阶段不初始化 React、Module Federation 或任一 Console 应用。`shared/api-client` 是无状态 TypeScript REST Client，由正式 OpenAPI 契约生成并在 Maven `verify` 中执行类型检查；它不实现认证、Cookie、CSRF 或 Token 存储。
