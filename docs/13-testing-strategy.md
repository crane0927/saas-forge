# saas-forge 测试策略

## 目标

测试策略验证领域规则、租户隔离、跨服务契约、完整 SaaS 闭环和生产运行目标。测试不以覆盖率替代安全验证；越权、失效、重放和并发超额必须有明确反向用例。

## 测试层级

| 层级 | 工具与环境 | 覆盖重点 |
|---|---|---|
| 单元测试 | JUnit 5 | 领域状态、RBAC、Plan / Subscription、错误映射、Token 与缓存策略 |
| 集成测试 | Testcontainers 的 PostgreSQL、Redis、Kafka、对象存储兼容服务 | Flyway、RLS、Outbox、Redis 黑名单、JWT、Quota 原子更新、Kafka 幂等消费 |
| 契约测试 | 版本化 OpenAPI 3.1、Protobuf | REST / gRPC 生产者—消费者兼容性、破坏性变更检测、生成 Client 可用性 |
| 前端测试 | Vitest、React Testing Library | Shell 菜单、权限可见性、认证 API、Remote 错误边界 |
| 端到端测试 | Docker Compose、Playwright | 登录、Tenant 切换、邀请激活、Role/Permission、Feature/Quota、Remote 加载、审计闭环 |
| 性能测试 | k6 | Gateway API、登录/刷新、授权/权益查询、Quota 扣减的延迟与错误率 |
| 安全测试 | 漏洞/镜像扫描、OWASP ZAP 基线扫描 | 依赖风险、Gateway 常见 Web 风险、错误安全性 |

## 强制安全用例

- Access Token：验签、过期、错误 `kid`、黑名单 `jti`、Redis 不可用 fail-closed、Tenant 切换后旧 Token 失效。
- Refresh Token：哈希存储、轮换、重放、登出、密码重置、成员禁用与 Tenant 冻结后的撤销。
- Client Credentials：Scope 最小化、Secret 轮换与吊销、服务 Token 不可冒充用户或 Tenant。
- RLS：以应用数据库角色连接，在 Tenant A 上下文中验证 Tenant B 数据不可读、不可写、不可更新、不可删除；无 Tenant 上下文默认拒绝。
- 授权与权益：平台/租户角色隔离、Permission 与 Feature 的组合拒绝、Subscription 到期、Quota 并发扣减不超额、`operationId` 重试幂等。
- 前端：未注册来源不可加载 Remote，Remote 不可读取 Token，菜单隐藏不作为后端授权替代。

## 核心端到端闭环

```text
部署平台
→ 平台管理员登录
→ 定义 Feature / Quota
→ 创建 Plan、Tenant、Subscription
→ 初始化 Tenant Admin
→ Tenant Admin 邀请并激活用户
→ 创建组织、Role 与 Permission
→ 用户登录并切换 Tenant
→ 业务服务接入 SDK
→ Permission / Feature / Quota Check
→ 执行业务并写入 Audit
```

Playwright 还必须覆盖 Tenant Console Shell 登录、菜单权限、微前端 Remote 加载与拒绝路径。

## 质量门禁

- 全仓库行覆盖率不低于 80%，分支覆盖率不低于 70%。
- IAM、Tenant Context、RLS、授权和配额关键模块行覆盖率不低于 90%。
- Maven 单元测试使用 `*Test`，集成与契约测试使用 `*IT`；JaCoCo 聚合两类测试后执行覆盖率门禁，具体模块清单与命令见 [Maven 构建与制品发布](21-maven-build-and-release.md)。
- `main` 仅能通过 Pull Request 合并，且必须通过测试、契约、覆盖率、漏洞、镜像和 ZAP 门禁。单人开发阶段不强制独立批准；团队出现第二位开发者后，要求至少一名独立审查者批准。
- 版本标签触发可追溯的制品和 Helm Chart 发布。

## 容量、性能与可用性验证

首期规划容量：20 个 Tenant、每 Tenant 500 名活跃用户，共 10,000 名活跃用户；峰值按 10% 同时在线，即 1,000 并发用户。按每位并发用户平均每 10 秒 1 次 API 请求，基线为 100 RPS，并验证 200 RPS 突发余量。审计按 100,000 条/日规划，并保留 2 倍增长余量。

除上传、导出和异步任务外，k6 必须验证外部 API p95 ≤ 300 ms、p99 ≤ 1 s。生产月度可用性目标为 99.9%；Gateway 成功请求率（排除客户端取消和预期 `4xx`）与登录、关键只读操作的黑盒探针共同形成 SLI。30 天周期的错误预算为 43.2 分钟。

开发环境只能通过依赖中断、容器重启、Kafka 延迟和 Redis 不可用等故障注入测试验证达标条件；真实 SLO 由生产遥测审计。
