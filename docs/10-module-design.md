# saas-forge 模块设计

## 顶层结构

```text
saas-forge
├── services
│   ├── iam-service
│   ├── tenant-access-service
│   ├── entitlement-service
│   └── audit-service
├── gateway
├── contracts
│   ├── openapi
│   ├── protobuf
│   └── events
├── sdk
│   ├── java
│   └── starters
├── consoles
│   ├── platform-console
│   ├── tenant-console-shell
│   ├── business-remotes
│   └── shared
├── examples
├── deploy
│   ├── compose
│   ├── helm
│   └── systemd
├── docs
└── scripts
```

API Gateway 是边界组件，不计入领域服务数量。它不持有领域数据，也不承载领域规则。

## 服务边界与数据所有权

| 服务 | 负责的领域 | 独占数据 | 同步协作 |
|---|---|---|---|
| `iam-service` | Identity、密码凭据、邀请激活、会话、JWT、Refresh Token、Client Credentials、JWKS | Identity、Credential、Refresh Token、OAuth Client / Secret 元数据 | 登录与 Tenant 切换时调用 Tenant Access 验证 Membership |
| `tenant-access-service` | Tenant、Membership、Organization、RBAC、Permission、业务能力注册 | Tenant、Membership、Organization、Role、Permission、关联表、邀请 | 为 IAM、SDK 提供成员和授权查询 |
| `entitlement-service` | Plan、Subscription、Feature、Quota | Plan、订阅版本与权益快照、Quota Definition / Usage / Operation | 为 SDK 提供权益与配额的强一致判定 |
| `audit-service` | 统一审计、审计查询与导出任务 | 只追加 Audit Record、导出任务元数据 | 消费其他服务与业务系统的审计事件 |

服务之间禁止共享领域代码、实体和数据库表。跨服务共享物仅为版本化 OpenAPI / Protobuf / 事件契约、通用安全与可观测性库以及构建 BOM。

## 通信规则

```text
External client → Gateway → REST service endpoint
IAM ↔ Tenant Access → gRPC
SDK → Gateway → REST runtime endpoint
All services → Kafka Outbox → Audit / cache invalidation consumers
```

- 需要即时结论的认证、成员校验、Permission / Feature 查询与 Quota 判定走同步路径。
- Tenant、Membership、Identity、Subscription 与权益状态变更通过 Kafka 通知其他服务收敛缓存和审计。
- 所有领域事件采用 Transactional Outbox：业务事务同时写入领域数据和 Outbox；发布器可靠投递 Kafka；消费者按事件 ID 幂等。

## 前端模块

`platform-console` 与 `tenant-console-shell` 是独立部署应用。Shell 统一处理登录、Tenant 切换、路由、菜单、错误边界和共享依赖；业务模块以 Module Federation Remote 独立构建、独立部署。

Remote 仅能由经审核的版本化 Manifest 加载。Manifest 由业务模块 CI 以 Client Credentials 注册，包含远程入口、页面、菜单、Permission 与 Feature；平台管理员只能审核、启停和查看。Remote 只能使用 Shell 暴露的认证 API 与共享 HTTP Client，不能读取或存储 Token。

## 模块依赖方向

- Gateway 可依赖契约与通用安全/可观测性库，不依赖领域实现。
- 每个领域服务可依赖自身领域模块、契约与通用库；不得反向依赖其他服务的领域实现。
- SDK 只依赖公共 REST / JWKS 契约，不依赖内部 gRPC 或领域数据库模型。
- Console 只依赖公开 API Client 和注册的前端契约，不直接访问服务数据库。
