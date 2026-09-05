# Java SDK Client 从显式安全子集生成

正式 OpenAPI v1 仍是 REST 契约的唯一可编辑来源；只有经评审并声明 `x-saasforge-java-sdk: true` 的 operation 才发布到 `sdk-core` Java Client，缺少标记即默认不发布。`sdk-core` 构建在 `target` 中派生仅含获批 operation、所需组件及其引用闭包的临时 OpenAPI 视图，再由 OpenAPI Generator 生成并编译 Client；派生视图和生成代码均不提交，也不能独立编辑。服务端接口与 TypeScript Client 继续直接使用完整正式契约，因此该标记不改变 operation 的服务归属、认证策略或 HTTP 语义。

机械发布全部 operation 会把 HttpOnly Refresh Cookie、`Origin`、Fetch Metadata 等浏览器管理的安全边界降格为普通 Java 参数，并让服务端消费者误用浏览器会话流程。显式 opt-in 使 Java 发布面可审查且默认收敛，同时保留一份契约和可重复生成。首版 Java Client 仅是低层 HTTP 契约镜像：消费者必须显式设置 Gateway 地址并通过生成 Client 的请求配置提供 operation 所需凭证；默认地址使用保留的 `.invalid` 域名。首版不增加手写 DTO、领域 façade、自动重试、熔断或完整 Problem Details 异常映射。
