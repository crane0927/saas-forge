# saas-forge IAM 设计

## IAM 能力范围

IAM（Identity and Access Management）覆盖身份、登录、密码、Token、Session、租户身份切换、认证、平台管理员身份、租户用户身份、API Key 与 Client Credential。

后续可扩展 OAuth 2.0、OIDC、SSO、LDAP、企业身份源与第三方登录；第一阶段不要求一次全部实现。

## Identity 与 Membership

Identity 表示一个平台中的自然人或机器身份。不能将 `User` 等同于 Tenant User：一个 Identity 可加入多个 Tenant。

Membership 表示该 Identity 作为成员加入某 Tenant。租户级授权绑定 Membership，而非直接绑定全局 Identity，因此同一用户可在不同 Tenant 获得不同角色和权限。

## Organization

Organization 描述租户内的层级结构。LIS 中可以是医院、门诊部、检验科和组；CRM 中可以是企业、区域和团队。模型采用通用的 `Organization`、`OrganizationUnit`、`Membership`，不局限于 `Department`。

## RBAC 与权限

首期权限模型：

```text
Membership → Role → Permission
```

平台权限与租户权限必须隔离。示例：

- 平台角色：`tenant:create`、`tenant:disable`、`plan:create`、`subscription:update`；
- 租户角色：`system:user:create`、`system:role:update`；
- 业务注册权限：`lis:specimen:list`、`lis:report:create`、`lis:report:view`、`lis:report:audit`、`lis:report:publish`。

平台不理解业务权限的业务含义，只负责定义、注册、绑定、授权与校验。Permission 统一建议采用 `namespace:resource:action`，如 `system:user:list`、`lis:report:audit`、`crm:customer:create`。`system` 为平台内置命名空间，业务项目自行维护其命名空间。
