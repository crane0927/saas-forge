# Gateway 使用 Spring Cloud Gateway Server MVC

Gateway 在 MVP 的最小路由阶段使用 Spring Cloud Gateway Server MVC，而不引入 Nacos、服务发现或 Reactive Gateway。根项目固定到 Spring Boot 4.0.x 并导入与之兼容的 Spring Cloud Release Train；公开路由以代码中的 OpenAPI 白名单定义，部署配置只提供 IAM、Tenant Access 和 Entitlement 的基础地址。这样保留 Servlet/MVC 运行模型，并避免部署配置意外扩展公开 API。

Gateway 只生成自身的 `ROUTE_NOT_FOUND`、`METHOD_NOT_ALLOWED`、`UPSTREAM_INVALID_RESPONSE` 与 `UPSTREAM_TIMEOUT` Problem Details；合格的下游 Problem Details 原样透传。它仅透传或新建 W3C `traceparent`／`tracestate`，不自动重试上游请求，也不信任或转发客户端给出的 `Forwarded`、`X-Forwarded-*` 头，直到来源策略明确受信代理边界。
