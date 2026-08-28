# 受控 Service Registry 与共享 Route Catalog

公开 HTTP method、path 与认证策略继续只由正式 OpenAPI 定义；受版本控制的 Service Registry 只声明可部署服务身份、稳定 Nacos 名称和能否成为 Gateway 路由目标，固定 OAuth Scope Registry 则声明 Scope 所有权及公网路由资格。构建工具以语义级 OpenAPI Parser 同时校验三者，并生成带版本的不可变 Route Catalog 契约制品，供 Gateway 与统一认证 Starter 共同加载；Nacos 只为已登记目标发现健康实例，注册本身不能增加公网入口。

选择标准 OAuth2 Client Credentials Security Scheme 表达 Service-required operation 和 required scopes；User、Service、Refresh Cookie 与 OAuth Client Basic 认证保持可区分。Gateway 与下游从同一 Catalog 选择认证策略并分别复验原始 Token，不信任普通上下文 Header，也不在 Gateway 建立 Role、Permission、资源级授权或 Service Tenant Context。新增公开路由仍需修改正式 OpenAPI、重新生成和滚动部署相关制品；平台不建设运行时动态公网路由控制面。

## Considered Options

- 继续维护 Gateway `Target` 枚举、owner switch 和手写下游白名单：拒绝，因为现有三处硬编码已形成漂移风险，新增服务必须修改 Gateway Java。
- 由 Nacos 或运行时配置动态增加公开路由：拒绝，因为服务注册与公网暴露必须是两个独立安全边界。
- 使用独立 Service Bearer Scheme 与自定义 Scope 扩展：拒绝，因为标准 OAuth2 Client Credentials 能直接表达 Token endpoint 和 operation scopes，更利于 SDK 与构建工具理解。
