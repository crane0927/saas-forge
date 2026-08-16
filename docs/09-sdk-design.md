# saas-forge SDK 设计

## 定位

SDK 是 `saas-forge` 与业务系统间最重要的集成层之一。首期提供 Java SDK 与 Spring Boot Starter；未来可视需求提供 Node.js、Go、Python、.NET SDK。

## Java SDK 模块

初步模块建议：

```text
saas-forge-java
├── saas-forge-sdk-core
├── saas-forge-sdk-auth
├── saas-forge-sdk-tenant
├── saas-forge-sdk-permission
├── saas-forge-sdk-feature
├── saas-forge-sdk-quota
├── saas-forge-sdk-audit
└── saas-forge-spring-boot-starter
```

是否拆分为上述细粒度模块，需在实际编码阶段验证。

## Spring Boot Starter

业务项目通过以下依赖接入：

```xml
<dependency>
    <groupId>io.saasforge</groupId>
    <artifactId>saas-forge-spring-boot-starter</artifactId>
</dependency>
```

示例配置：

```yaml
saas-forge:
  endpoint: http://saas-forge:8080
  client-id: lis-server
  client-secret: ${SAAS_FORGE_CLIENT_SECRET}
```

具体认证机制留待安全设计阶段确定。

## 核心能力

| 能力 | 拟提供的使用方式 | 约束 |
|---|---|---|
| Tenant Context | `TenantContext.getTenantId()` | 必须由可信认证链路建立，不能由业务请求任意指定 |
| Identity Context | `IdentityContext.getIdentityId()` | 表示当前身份 |
| Membership Context | `MembershipContext.getMembershipId()` | 表示当前以哪个 Tenant 成员身份访问 |
| Permission | `@RequirePermission("lis:report:audit")` | 权限校验 |
| Feature | `@RequireFeature("lis.quality-control")` | 产品权益校验 |
| Quota | `quotaService.check("lis.device.count")` 或 `@RequireQuota("lis.device.count")` | 配额校验 |
| Audit | `@Audit("lis.report.publish")` | 关键操作审计 |

具体 SDK API 在后续设计阶段确定。

## 预期开发者体验

开发者启动 `docker compose up -d` 获得 Server 与两类控制台，在业务服务加入 Starter 后，即可在业务接口声明 `@RequireFeature` 与 `@RequirePermission`。业务代码聚焦标本查询、报告审核、订单计算、客户管理等领域能力，不必重复设计 Tenant、RBAC、套餐、Feature、Quota 和数据隔离。
