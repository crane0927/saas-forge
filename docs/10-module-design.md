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
│   ├── events
│   ├── redis
│   └── logging
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
| `iam-service` | Identity、密码凭据、会话、JWT、Refresh Token、Client Credentials、JWKS | Identity、Credential、Refresh Token、OAuth Client / Secret 元数据、会话与令牌撤销记录 | 登录与 Tenant 切换时调用 Tenant Access 验证 Membership；为 Tenant Access 提供 Identity/凭据建立与会话撤销 |
| `tenant-access-service` | Tenant、Membership、Organization、RBAC、Permission、邀请、初始管理员初始化 | Tenant、Membership、Organization、Role、Permission、关联表、Invitation、跨服务工作流记录与补偿/重试工作项 | 为 IAM、SDK 提供成员和授权查询；编排管理员初始化、邀请激活、成员禁用与 Tenant 冻结 |
| `entitlement-service` | Plan、Subscription、Feature、Quota | Plan、订阅版本与权益快照、Quota Definition / Usage / Operation | 为 SDK 提供权益与配额的强一致判定 |
| `audit-service` | 统一审计、审计查询与导出任务 | 只追加 Audit Record、导出任务元数据 | 消费其他服务与业务系统的审计事件 |

服务之间禁止共享领域代码、实体、数据库模型、数据库表和迁移。跨服务共享物仅为版本化 OpenAPI / Protobuf / 事件契约、Redis 安全基础设施契约、日志 Schema、通用安全与可观测性库以及构建 BOM。契约类型在服务边界映射为各服务自己的内部模型。

## 通信规则

```text
External client → Gateway → REST service endpoint
IAM ↔ Tenant Access → gRPC
SDK → Gateway → REST runtime endpoint
All services → Kafka Outbox → Audit / cache invalidation consumers
```

- 需要即时结论的认证、成员校验、Permission / Feature 查询、Quota 判定与会话撤销走同步路径；Kafka 不承担这些路径的成功判定。
- Tenant、Membership、Identity、Subscription 与权益状态变更通过 Kafka 通知其他服务收敛缓存和审计。流程根服务、同步调用顺序与恢复责任以[跨服务工作流契约](18-tenant-access-cross-service-workflows.md)为准。
- 所有领域事件采用 Transactional Outbox：业务事务同时写入领域数据和 Outbox；发布器可靠投递 Kafka；消费者按事件 ID 幂等。事件只表达来源服务已提交的事实，不作为跨服务命令。

## 前端模块

`platform-console` 与 `tenant-console-shell` 是独立部署应用。Shell 统一处理登录、Tenant 切换、路由、菜单、错误边界和共享依赖；业务模块以 Module Federation Remote 独立构建、独立部署。

Remote 仅能由经审核的版本化 Manifest 加载。Manifest 由业务模块 CI 以 Client Credentials 注册，包含远程入口、页面、菜单、Permission 与 Feature；平台管理员只能审核、启停和查看。Remote 只能使用 Shell 暴露的认证 API 与共享 HTTP Client，不能读取或存储 Token。

## 模块依赖方向

- Gateway 可依赖契约与通用安全/可观测性库，不依赖领域实现。
- 每个领域服务可依赖自身领域模块、契约与通用库；不得反向依赖其他服务的领域实现。
- SDK 只依赖公共 REST / JWKS 契约，不依赖内部 gRPC 或领域数据库模型。
- Console 只依赖公开 API Client 和注册的前端契约，不直接访问服务数据库。
