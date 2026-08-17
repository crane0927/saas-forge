# saas-forge 部署设计

## 交付形态

| 场景 | 交付方式 | 定位 |
|---|---|---|
| 本地开发与体验 | Docker Compose | 启动完整开发依赖，支持 Example 与 E2E |
| 标准生产 | Kubernetes + Helm | 领域服务独立扩缩容、滚动发布与高可用 |
| 兼容交付 | 虚拟机裸部署文档与 `systemd` 示例 | 适配不使用 Kubernetes 的环境；遵循相同网络、密钥、备份和监控要求 |

## Docker Compose

Compose 用于本地开发、演示和端到端测试，包含：

```text
API Gateway
iam-service
tenant-access-service
entitlement-service
audit-service
Platform Console
Tenant Console Shell
PostgreSQL（提供四个逻辑数据库）
Redis
Kafka
S3 兼容对象存储
OpenTelemetry Collector
```

本地环境可以使用单节点依赖，但不得把单节点拓扑等同于生产拓扑。

开发 JWT Signing Key 由显式本地初始化生成，保存于 `.gitignore` 的本地密钥目录，并只读挂载给 IAM。Compose 不自动创建或删除该密钥；需要轮换时必须通过显式本地操作重建。该密钥不得用于生产 profile。

开发与端到端测试的 `browser.rootDomain` 固定为 `saasforge.test`：`platform.saasforge.test`、`console.saasforge.test`、`api.saasforge.test`、`remote.saasforge.test` 都解析到 `127.0.0.1`，并经本地受信 TLS 反向代理提供 HTTPS。Quick Start 必须检查或说明本地域名解析与证书信任前置条件；不得用不同 `localhost` 端口替代该安全验收拓扑。

## 生产拓扑

```text
Browser / Business Application
            │ HTTPS
            ▼
       API Gateway (≥ 2 replicas)
            │ mTLS / gRPC or REST
    ┌───────┼────────┬─────────────┐
    ▼       ▼        ▼             ▼
 IAM (≥2) Tenant Access (≥2) Entitlement (≥2) Audit (≥2)
    │       │        │             │
    └───────┴────────┴──── Kafka ──┘
            │
 External PostgreSQL / Redis / Kafka / S3 / KMS / Observability
```

Platform Console、Tenant Console Shell 与业务 Remote 独立发布。Gateway 与四个无状态领域服务至少运行 2 个副本，配置滚动发布、readiness/liveness 探针和 PodDisruptionBudget。领域服务不直接暴露公网。

浏览器入口固定为同一完全受控可注册根域下的 `https://platform.<root>`、`https://console.<root>`、`https://api.<root>` 与 `https://remote.<root>/<module>/<version>`。前三者分别承载 Platform Console、Tenant Console Shell 和 Gateway；Remote 仅由受审的 `remote.<root>` 路径发布。每个 Origin 必须独立配置 TLS 与发布权限。

每个环境在非敏感部署配置中设置 `browser.rootDomain`，以生成上述固定 Origin 和 CORS 白名单。API Gateway 仅允许 Platform Console 与 Tenant Console Shell 的凭据型 CORS；Remote 静态资源仅允许 Tenant Console Shell 无凭据加载。禁止使用 CORS 通配符、`null` Origin 或由 Manifest/运行时改变该白名单。

## 有状态依赖

生产 Helm Chart 只接入外部提供的 PostgreSQL、Redis、Kafka、S3 兼容对象存储、密钥管理服务和可观测性后端；不在应用 Chart 内默认部署这些有状态组件。

- PostgreSQL 为四个服务提供独立数据库，需满足 RPO ≤ 5 分钟、RTO ≤ 30 分钟，并定期执行恢复演练。
- Redis 是 Token 黑名单、会话和登录保护的安全依赖，使用高可用主从与自动故障转移的托管服务或 Sentinel/等效方案。
- Kafka 至少 3 Broker，主题副本数 3、`min.insync.replicas=2`、生产者 `acks=all`。
- S3 兼容对象存储仅保存导出任务的临时结果。导出不按 Tenant/Plan 限额，但任务必须异步、流式处理、通过全局有界队列与单 Tenant 公平调度保护系统；结果文件按配置留存期自动删除。

## 网络、配置与密钥

- Gateway 是唯一公网入口，使用 HTTPS；服务间 gRPC 使用 mTLS，所有到 PostgreSQL、Redis、Kafka、对象存储的连接使用 TLS。
- 密钥由外部密钥管理服务托管。生产 JWT 私钥只保留在 KMS/HSM，IAM 在 Kubernetes 中通过受限工作负载身份调用签名接口，在虚拟机中仅通过服务账号可读的系统凭据文件取得该调用权限；其他密钥可受控同步挂载或以受限凭据文件注入。禁止将密钥写入镜像、代码或普通配置。
- 生产 Signing Key 的轮换周期由部署合规策略配置。常规轮换按“发布新 `kid` → 等待 5 分钟 → 切换签名 → 保留旧公钥至少 30 分钟 → 禁用旧版本并移除 JWKS”执行；疑似泄露时立即撤销旧 `kid`，并验证 Gateway 与所有业务服务均已拒绝它后才完成事件处置。
- Helm values 与环境变量只包含非敏感配置，如服务地址、资源限额、限流和保留期策略；敏感值只引用密钥管理系统。
- API 的凭据型 CORS 仅允许 Platform Console 与 Tenant Console Shell；Remote 静态资源仅允许 Tenant Console Shell 无凭据加载。Remote 的入口和版本由 Manifest 白名单控制。

## 可观测性、SLO 与容量

所有组件导出 OpenTelemetry 数据到 Collector，并接入 Prometheus、Loki、Tempo 和 Grafana。Gateway 按路由与状态码记录请求成功率、延迟和错误预算消耗；黑盒探针验证登录与关键只读操作。

月度 SLO 为 99.9%，范围是 Gateway 暴露的 Platform 与 Tenant Console API。规划容量为 20 个 Tenant、10,000 名活跃用户、1,000 峰值并发用户、100 RPS 基线、200 RPS 突发余量以及 100,000 条审计事件/日（2 倍增长余量）。实际 Pod 资源请求和副本上限须由该压测基线的报告确定，不在文档中虚构固定规格。

## 发布、回滚与变更审计

- GitHub Actions 执行测试、契约、覆盖率、镜像与漏洞扫描、ZAP 基线扫描和 Helm 验证。
- `main` 必须经 Pull Request 并通过所有自动门禁；单人开发阶段不强制独立批准，团队增加第二位开发者后要求至少一名独立审查者批准。
- 受保护的 `vX.Y.Z` 标签在 JDK 17/21门禁通过后，由 JDK 17向 Maven Central 发布签名的 SDK、Starter 与 BOM；Maven 发布约定见 [Maven 构建与制品发布](21-maven-build-and-release.md)。镜像与 Helm Chart 仍由各自发布流程处理。每次部署记录版本、迁移、配置版本、操作者、开始/完成时间和回滚结果。
- Flyway 迁移随服务版本发布。生产变更先在等效环境验证；失败时回滚应用版本，数据库迁移按事先验证的前向修复或可逆方案处理。

## 虚拟机裸部署

虚拟机方案以四个独立服务、Gateway、Console 静态资源和受管外部依赖组成。每个服务由独立 `systemd` 单元运行，配置健康检查、受限账号、凭据文件、日志转发和自动重启；不得把所有服务、数据库和 Kafka 压缩为无隔离的单一进程。
