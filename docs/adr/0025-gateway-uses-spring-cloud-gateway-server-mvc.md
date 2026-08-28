# Gateway 使用 Spring Cloud Gateway Server MVC 与 Nacos

Gateway 与领域服务使用 Nacos 进行服务注册、发现和非敏感运行配置管理，同时继续使用 Spring Cloud Gateway Server MVC，不引入 Reactive Gateway。Gateway 和服务客户端按服务名发现健康实例，避免依赖固定部署地址；公开路由及其认证分类在构建时从正式 OpenAPI v1 生成，服务注册或配置变更不得增加公网入口。密码、数据库凭据、OAuth Client Secret 和 JWT/KMS 凭据仍由外部密钥管理服务托管，不写入 Nacos 普通配置。

Docker Compose 为本地开发提供单节点、非 TLS 的 Nacos；生产环境接入独立部署的高可用 HTTPS Nacos 集群或其高可用接入端点，应用 Helm Chart 只引用该端点且不部署该集群。生产工作负载通过外部密钥管理服务注入各自的 Nacos 身份，Config Client 启用 TLS，Naming Client 以 `-Dcom.alibaba.nacos.client.naming.tls.enable=true` 启用 TLS。

Nacos 配置默认随版本滚动发布生效；只有被显式标记为可热更新的低风险策略才允许动态刷新。服务发现可动态收敛健康实例，但公开路由白名单、认证与 CORS 边界、TLS、数据库连接和迁移不得通过配置热更新改变。

`dev`、`test`、`staging` 与 `prod` 各自使用独立的 Nacos namespace。服务注册、发现和配置读取不得跨越环境 namespace。

每个可部署应用在 `SAAS_FORGE` group 中拥有一份配置资源：`gateway.yaml`、`iam-service.yaml`、`tenant-access-service.yaml`、`entitlement-service.yaml` 或 `audit-service.yaml`。当前不设置共享配置资源；任何跨服务配置都必须在需要时明确其所有者、使用者和覆盖规则。

Gateway 和每个领域服务各使用独立的 Nacos 工作负载身份，只能注册、发现服务并读取自己的配置。生产配置写入仅授予受控发布流水线；人工运维使用独立、可审计的应急身份。

Nacos 注册名固定为 `gateway`、`iam-service`、`tenant-access-service`、`entitlement-service` 和 `audit-service`。新业务服务以稳定模块名注册；Gateway 的构建期不可变 Route Catalog只将正式 OpenAPI已声明的 API映射到受控 Service Registry服务名，不因新服务注册而自动增加公网入口。Registry、Catalog与 User/Service认证模型的演进见 [ADR 0034](0034-controlled-service-registry-and-route-catalog.md)。

客户端使用 Spring Cloud Alibaba 2025.1.x BOM（首个锁定版本为 `2025.1.0.0`）及 Nacos Config、Discovery Starter；配置通过 `spring.config.import` 导入，并启用 Nacos 的 Spring Cloud LoadBalancer 集成以按服务名选择健康实例。

生产环境的应用与 Nacos 之间必须使用 TLS，Nacos 工作负载身份凭据由外部密钥管理服务注入。本地 Compose 可在隔离网络中使用非 TLS 开发连接，但不得使用 Nacos 默认账户或任何生产凭据。

非敏感 Nacos 配置以仓库中受版本控制的配置清单为权威来源，由 CI 校验并发布。Nacos Console 仅用于查看和受审计的应急处置；任何应急变更都必须回写仓库并经 Git 复核。

Nacos 运行时不可用时，已启动实例继续使用最后一次成功加载的配置与已知健康实例。新实例若不能加载必需配置或完成注册不得 Ready；Gateway 找不到健康实例时返回 `503`，不得回退到静态服务地址。

Gateway 只生成自身的 `ROUTE_NOT_FOUND`、`METHOD_NOT_ALLOWED`、`UPSTREAM_UNAVAILABLE`、`UPSTREAM_INVALID_RESPONSE`、`UPSTREAM_TIMEOUT`、`ACCESS_TOKEN_INVALID` 与 `TOKEN_REVOCATION_STATUS_UNAVAILABLE` Problem Details；无健康服务实例时使用稳定的 `503 UPSTREAM_UNAVAILABLE`。OpenAPI 的 UserBearerAuth 安全声明决定 User Token 为 required、optional 或 anonymous：Gateway 使用共享 SDK 完成签名与声明校验，再读取 IAM Redis Revocation Index。optional 的登出请求即使携带无效或已撤销 Token 也会到达 IAM，以完成 Refresh Cookie 清理；撤销索引未就绪或不可用属于无法安全判定，仍以 `503 TOKEN_REVOCATION_STATUS_UNAVAILABLE` 失败关闭。合格的下游 Problem Details 原样透传。它仅透传或新建 W3C `traceparent`／`tracestate`，不自动重试上游请求，也不信任或转发客户端给出的 `Forwarded`、`X-Forwarded-*` 头，直到来源策略明确受信代理边界。
