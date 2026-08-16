# saas-forge API 设计

## 集成边界

`saas-forge` 对外提供 REST + JSON API；业务系统也可通过 Java SDK 接入。业务系统禁止直接读取或写入任一平台服务数据库。

```text
Browser / Business Application
            │ HTTPS + JSON
            ▼
       API Gateway
            │
    ┌───────┼────────┬─────────────┐
    ▼       ▼        ▼             ▼
  IAM   Tenant Access Entitlement Audit
```

API Gateway 是唯一公网入口，负责 TLS 终止、JWT 初步校验、限流、路由、CORS 与 `traceId` 透传。领域服务不直接暴露公网。

## 契约与版本

- OpenAPI 3.1 是正式且受版本控制的 REST 契约；Swagger UI 只可作为查看和调试界面。
- 契约采用 spec-first：先审查 OpenAPI，再生成服务端接口骨架、Java REST Client 与前端 API Client；实现不得反向修改契约。
- 公共 API 使用 URI 主版本，例如 `/api/v1/...`；破坏性变更进入新主版本。
- 内部同步接口使用版本化 Protobuf；Kafka 事件使用 CloudEvents JSON 与版本化类型，例如 `com.saasforge.tenant.suspended.v1`。

## v1 资源边界

以下路径是 v1 的资源分组，具体请求/响应 Schema 以 OpenAPI 3.1 文件为准。

| 路径前缀 | 服务 | 资源与操作 |
|---|---|---|
| `/api/v1/auth` | IAM | 登录、刷新、登出、租户成员身份切换、邀请激活 |
| `/oauth2/token` | IAM | 仅服务间 Client Credentials；不承载面向用户的第三方授权登录 |
| `/.well-known/jwks.json` | IAM | 版本化 JWKS 公钥发布 |
| `/api/v1/platform` | Tenant Access、Entitlement | Tenant、平台管理员、Plan、Subscription、Feature、Quota 定义和能力注册 |
| `/api/v1/tenant` | Tenant Access | Membership、Organization、Role、Permission、邀请与租户设置 |
| `/api/v1/runtime` | Tenant Access、Entitlement | 业务系统的 Permission / Feature 查询与 Quota `check`、`consume`、`release` |
| `/api/v1/audit` | Audit | 经过授权的审计查询与导出任务 |

用户 Token 的 Tenant 由已验证的 `membershipId` 决定；用户请求不得以请求头、查询参数或请求体覆盖 Tenant。Client Credentials 令牌只代表 `client_id` 与 `scope`，不伪造用户、Membership 或 Tenant 身份。

## REST 约定

### 表示与标识

- 请求与响应使用 UTF-8 JSON；实体 ID 为 UUIDv7 字符串。
- 资源路径使用复数名词；创建、读取、替换、部分更新、删除分别使用 `POST`、`GET`、`PUT`、`PATCH`、`DELETE`。
- 非同步导出、导入或长耗时操作返回 `202 Accepted` 和任务资源；结果通过短期签名 URL 获取。

### 分页与幂等

- 所有集合查询统一使用 `limit` 与不透明 `cursor`；响应包含 `items`、`nextCursor`、`hasMore`。
- 创建和其他具有外部可见状态变更的请求支持 `Idempotency-Key`。服务端按调用方与键保存首次结果，在有效期内返回相同结果。
- Quota 的 `consume` 与 `release` 额外要求稳定 `operationId`，避免网络重试重复扣减或释放。

### 状态与错误

- 成功使用语义正确的 `200`、`201`、`202`、`204`；认证、授权、资源和并发错误分别使用相应 `4xx` 状态。
- 失败响应采用 `application/problem+json`，至少包含稳定业务 `code`、`detail`、HTTP 状态和 `traceId`。
- 客户端应按 `code` 编程，不得解析 `detail` 文本。

## 认证、来源与限流

- 用户请求使用约 15 分钟的 JWT Access Token；刷新令牌由浏览器以 HttpOnly Cookie 携带。
- Client Credentials 按 OAuth 2.0 标准实现，服务令牌只含 `client_id` 与显式 `scope`。
- CORS 默认拒绝，仅允许 Platform Console、Tenant Console Shell 和经审核登记的业务微前端来源；禁止通配符来源和携带凭据的通配配置。
- Gateway 基于 Redis 令牌桶，按 IP、Identity、Client、Tenant 维度限流；阈值由环境配置，不写死在代码中。

## 业务能力与前端模块注册

业务模块通过 Client Credentials 注册版本化 Manifest，至少声明：模块标识、远程入口、页面与菜单、所需 Permission、Feature、Quota 定义。CI 是注册主体；平台管理员仅审核、启停和查看，不能手工登记任意远程脚本地址。

业务 Permission、Feature、Quota Definition 也通过该注册契约进入平台。Tenant Console Shell 依据当前用户权限、Tenant 权益和已审核 Manifest 决定菜单及页面可见性；后端仍须独立执行授权与权益校验。
