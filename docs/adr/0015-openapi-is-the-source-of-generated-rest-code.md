# OpenAPI 是生成 REST 代码的唯一来源

正式 OpenAPI 契约是服务端 Spring MVC 接口骨架、`sdk-core` Java REST Client 和控制台 TypeScript API Client 的唯一输入；生成物不提交，手写 Controller 只能实现其所属 operation 的生成接口且不得自行声明 HTTP 路由。每个 operation 以唯一 `x-saas.forge-service` 标明领域服务归属，`tags` 只保留分组职责；这样在保留服务领域模型私有性的同时，让公开 HTTP 行为可重复生成并由构建防止实现漂移。

`sdk-core` Java REST Client 的范围已由 [ADR 0041](0041-java-sdk-client-is-generated-from-an-explicit-safe-subset.md) 收窄：只有经评审并显式声明 `x-saas.forge-java-sdk: true` 的 operation 才发布到该客户端。浏览器会话以及依赖 Cookie、`Origin` 或 Fetch Metadata 的 operation 不得获得该标记，缺少标记即默认不发布。服务端接口骨架与控制台 TypeScript Client 仍以正式契约为唯一输入。
