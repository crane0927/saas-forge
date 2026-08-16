# saas-forge SDK 设计

## 定位与版本

Java SDK 与 Spring Boot Starter 是 Java 业务服务接入平台的正式集成层。SDK 与 Starter 使用语义化版本，并通过 BOM 统一锁定模块版本；破坏性 API 仅在主版本升级时引入。

```text
saas-forge-java
├── saas-forge-bom
├── saas-forge-sdk-core
├── saas-forge-sdk-auth
├── saas-forge-sdk-tenant
├── saas-forge-sdk-permission
├── saas-forge-sdk-feature
├── saas-forge-sdk-quota
├── saas-forge-sdk-audit
└── saas-forge-spring-boot-starter
```

业务服务通过 `io.saasforge:saas-forge-spring-boot-starter` 接入。除 Java 外的 SDK 不属于首期范围。

## 身份与上下文

Starter 将 Spring Security Resource Server 与 IAM JWKS 端点集成：按 JWT `kid` 缓存公钥并支持签名密钥轮换。用户 Access Token 只包含：

```text
identityId
membershipId
tenantId
jti
```

SDK 对每个用户请求验证签名、有效期和 Redis `jti` 黑名单；黑名单不可用时 fail-closed。SDK 提供只读上下文：

```java
TenantContext.getTenantId();
IdentityContext.getIdentityId();
MembershipContext.getMembershipId();
```

上下文只能由已验证 Token 建立，业务代码不得由请求参数覆盖。Client Credentials 令牌不建立上述用户上下文。

## 授权、权益与配额

| 能力 | SDK 行为 | 一致性规则 |
|---|---|---|
| Permission | `@RequirePermission` 或编程式检查 | JWT 不携带权限；SDK 先查本地短缓存，未命中时经 Gateway 查询 Tenant Access；Kafka 事件失效缓存 |
| Feature | `@RequireFeature` 或编程式检查 | 与 Permission 相同；两项校验可同时要求 |
| Quota | `check`、`consume`、`release`、`usage` | 始终同步调用 Entitlement；`consume/release` 带稳定 `operationId`，不以本地缓存作为额度真相 |
| Audit | `@Audit` 或 `audit.log` | 将最小必要审计事件异步投递到 Audit 服务；不得记录凭据或原始敏感个人信息 |

业务应用可声明：

```java
@RequireFeature("lis.report")
@RequirePermission("lis:report:list")
public List<Report> list() {
    return reportService.list();
}
```

## 服务调用与韧性

- SDK 对平台 REST API 使用 `application/problem+json` 中的 `code` 进行异常映射，保留 `traceId`。
- 仅自动重试幂等读取和携带稳定幂等键的写入；超时、退避、重试上限和熔断均可配置。
- 无幂等保护的写入失败必须返回调用方显式处理，不自动重试。
- 业务系统通过 API / SDK 集成，不获得平台数据库访问权限。

## Starter 配置边界

配置包含 Gateway 地址、JWKS 地址、服务端 Client Credentials、超时/重试和缓存策略。Client Secret 不得写入代码或普通配置文件，应由运行环境的受控密钥注入提供。

Tenant Context、授权和审计的公共 API 是稳定集成面；平台内部服务或数据库实体不是 SDK 兼容性承诺。
