# Java SDK 与 Starter

此目录提供 Java SDK、BOM 与 Spring Boot Starter 的稳定制品坐标。

`saas-forge-sdk-core` 从唯一正式 OpenAPI v1 契约中显式标记为 `x-saasforge-java-sdk: true` 的安全子集生成低层 Java REST Client。构建期过滤视图与生成代码只存在于 `target`，不提交且不能独立编辑；浏览器登录、刷新、Password Setup、Context Selection、登出与 Tenant Context Switch 不进入 Java API，也不会暴露 HttpOnly Cookie、`Origin` 或 Fetch Metadata 参数。

消费者通过生成的 `ApiClient` 显式设置 Gateway 地址，并用请求配置提供 operation 所需的 Basic 或 Bearer 凭证。默认地址为不可部署的 `https://api.example.invalid`。首版 `sdk-core` 保留生成器的传输失败契约，不提供手写 DTO、自动重试、熔断、领域 façade 或完整 Problem Details 异常层。
