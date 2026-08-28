# saas-forge 部署设计

## 交付形态

| 场景 | 交付方式 | 定位 |
|---|---|---|
| 本地开发与体验 | Docker Compose | 启动完整开发依赖，支持 Example 与 E2E |
| 标准生产 | Kubernetes + Helm | 领域服务独立扩缩容、滚动发布与高可用 |
| 兼容交付 | 虚拟机裸部署文档与 `systemd` 示例 | 适配不使用 Kubernetes 的环境；遵循相同网络、密钥、备份和监控要求 |

## Docker Compose

完整 Compose 目标用于本地开发、演示和端到端测试，包含：

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
Nacos
S3 兼容对象存储
OpenTelemetry Collector
```

当前第 1 阶段的最小 Compose 仅包含 Gateway、四个领域服务、PostgreSQL、Redis、Kafka、OpenTelemetry Collector 和各服务的 Flyway 迁移任务。Platform Console、Tenant Console Shell、业务 Remote 与 S3 兼容对象存储随对应业务阶段加入；对象存储不早于第 6 阶段。

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
External PostgreSQL / Redis / Kafka / Nacos / S3 / KMS / Observability
```

Platform Console、Tenant Console Shell 与业务 Remote 独立发布。Gateway 与四个无状态领域服务至少运行 2 个副本，配置滚动发布、readiness/liveness 探针和 PodDisruptionBudget。领域服务不直接暴露公网。

浏览器入口固定为同一完全受控可注册根域下的 `https://platform.<root>`、`https://console.<root>`、`https://api.<root>` 与 `https://remote.<root>/<module>/<version>`。前三者分别承载 Platform Console、Tenant Console Shell 和 Gateway；Remote 仅由受审的 `remote.<root>` 路径发布。每个 Origin 必须独立配置 TLS 与发布权限。

每个环境在非敏感部署配置中设置 `browser.rootDomain`，以生成上述固定 Origin 和 CORS 白名单。API Gateway 仅允许 Platform Console 与 Tenant Console Shell 的凭据型 CORS；Remote 静态资源仅允许 Tenant Console Shell 无凭据加载。禁止使用 CORS 通配符、`null` Origin 或由 Manifest/运行时改变该白名单。

## 有状态依赖

生产 Helm Chart 只接入外部提供的 PostgreSQL、Redis、Kafka、Nacos、S3 兼容对象存储、密钥管理服务和可观测性后端；不在应用 Chart 内默认部署这些有状态组件。

- PostgreSQL 为四个服务提供独立数据库，需满足 RPO ≤ 5 分钟、RTO ≤ 30 分钟，并定期执行恢复演练。
- Redis 是 Token 黑名单、会话和登录保护的安全依赖，使用高可用主从与自动故障转移的托管服务或 Sentinel/等效方案。
- Kafka 至少 3 Broker，主题副本数 3、`min.insync.replicas=2`、生产者 `acks=all`。生产者 topic 固定为 `saasforge.<environment>.<producer-service>.events`；Kafka ACL 仅授予服务写入自己的 topic、读取事件工程注册表允许的 topic，以及消费者自己的隔离 topic 写入权限。
- Nacos 在本地 Compose 使用单节点；生产使用独立部署的高可用集群或其高可用 HTTPS 接入端点，供 Gateway 与领域服务注册、发现健康实例并读取非敏感运行配置。应用 Chart 仅引用该外部端点，不部署 Nacos Server。
- S3 兼容对象存储仅保存导出任务的临时结果。导出不按 Tenant/Plan 限额，但任务必须异步、流式处理、通过全局有界队列与单 Tenant 公平调度保护系统；结果文件按配置留存期自动删除。

## 网络、配置与密钥

- Gateway 是唯一公网入口，使用 HTTPS；服务间 gRPC 使用 mTLS，生产环境所有到 PostgreSQL、Redis、Kafka、Nacos、对象存储的连接使用 TLS。
- 密钥由外部密钥管理服务托管。生产 JWT 私钥只保留在 KMS/HSM，IAM 在 Kubernetes 中通过受限工作负载身份调用签名接口，在虚拟机中仅通过服务账号可读的系统凭据文件取得该调用权限；其他密钥可受控同步挂载或以受限凭据文件注入。禁止将密钥写入镜像、代码或普通配置。
- 生产 Signing Key 的轮换周期由部署合规策略配置。常规轮换按“发布新 `kid` → 等待 5 分钟 → 切换签名 → 保留旧公钥至少 30 分钟 → 禁用旧版本并移除 JWKS”执行；疑似泄露时立即撤销旧 `kid`，并验证 Gateway 与所有业务服务均已拒绝它后才完成事件处置。
- Nacos 管理非敏感运行配置；Helm values 与环境变量仅保留 Nacos 连接信息及不能由 Nacos 管理的非敏感部署参数。敏感值只引用密钥管理系统。
- Nacos 配置默认随版本滚动发布生效；仅显式标记的低风险策略可动态刷新。服务发现可动态收敛健康实例，公开路由白名单、认证/CORS、TLS、数据库连接与迁移不得热更新。
- 每个环境使用独立的 Nacos namespace；服务注册、发现和配置读取不得跨环境 namespace。
- Nacos 在 `SAAS_FORGE` group 中按可部署应用保存独立配置资源：`gateway.yaml`、`iam-service.yaml`、`tenant-access-service.yaml`、`entitlement-service.yaml`、`audit-service.yaml`。当前不设置共享配置资源。
- Gateway 与每个领域服务使用独立的 Nacos 工作负载身份，只能注册、发现服务并读取自身配置；生产配置写入仅允许受控发布流水线，人工运维使用独立、可审计的应急身份。
- Nacos 工作负载身份凭据由外部密钥管理服务注入。本地 Compose 可在隔离网络中使用非 TLS 开发连接，但不得使用 Nacos 默认账户或任何生产凭据。
- 生产应用为 Nacos Config Client 设置 `NACOS_TLS_ENABLED=true`，并通过 `JAVA_TOOL_OPTIONS` 设置 `-Dcom.alibaba.nacos.client.naming.tls.enable=true`；五个工作负载的用户名和密码均通过各自的外部 Secret 注入。具体 Chart 接口见 [`../deploy/helm/nacos-production-contract.yaml`](../deploy/helm/nacos-production-contract.yaml)。
- Nacos 注册名固定为 `gateway`、`iam-service`、`tenant-access-service`、`entitlement-service` 和 `audit-service`；新业务服务以稳定模块名注册。Gateway 仅将代码白名单中的 API 映射至相应服务名，不因注册自动开放公网入口。
- 非敏感 Nacos 配置以仓库中受版本控制的配置清单为权威来源，由 CI 校验并发布。Nacos Console 仅用于查看和受审计的应急处置；任何应急变更都必须回写仓库并经 Git 复核。
- 清单目录、CI 发布身份、最小权限矩阵、回滚和 Console 应急回写步骤由 [`deploy/nacos/README.md`](../deploy/nacos/README.md) 约束；生产发布使用受保护的 GitHub Environment。
- Nacos 运行时不可用时，已启动实例继续使用最后一次成功加载的配置与已知健康实例；新实例若不能加载必需配置或完成注册不得 Ready。Gateway 找不到健康实例时返回 `503`，不得回退到静态服务地址。
- Gateway 的生产路由只来自随制品发布的版本化 Route Catalog；Nacos 仅为 Catalog 已允许的 `serviceId` 提供健康实例发现，不得成为开放路由的来源。Service Registry、Scope Registry、OpenAPI 与 Catalog 不通过 Nacos 动态刷新，变更须走契约审查、构建门禁和受控发布。
- Audit 的 Kafka bootstrap、数据库凭据和其他敏感连接材料由部署侧 Secret/环境注入；重试、退避、隔离恢复策略写入 `audit-service` 专属 Nacos 资源并保持 `refreshEnabled=false`。Audit 只有在 Nacos 必需配置已加载、数据库可用且迁移完成、Kafka 连接成功并取得两个消费者的目标分区分配后才 Ready；任一前提失效时退出 Ready，不能以健康 HTTP 进程掩盖无法消费的状态。完整配置矩阵见 [Audit 成功事实消费设计](24-audit-success-fact-consumption.md)。
- 本地可用 `bash scripts/verify-nacos-failure-recovery.sh` 对上述行为执行隔离的 Compose 故障注入验收；脚本不会停止已有开发栈，并在退出时删除自己的临时容器和卷。
- API 的凭据型 CORS 仅允许 Platform Console 与 Tenant Console Shell；Remote 静态资源仅允许 Tenant Console Shell 无凭据加载。Remote 的入口和版本由 Manifest 白名单控制。

## 可观测性、SLO 与容量

所有组件导出 OpenTelemetry 数据到 Collector，并接入 Prometheus、Loki、Tempo 和 Grafana。Gateway 按路由与状态码记录请求成功率、延迟和错误预算消耗；黑盒探针验证登录与关键只读操作。

每个事件生产服务还必须监控 Outbox 最早待发布年龄、待发布数量、租约/重试、发布成功失败与耗时；消费者监控处理延迟、重复命中、校验拒绝、隔离数量与最早隔离年龄。告警阈值属于环境配置，日志不得输出事件 payload。

月度 SLO 为 99.9%，范围是 Gateway 暴露的 Platform 与 Tenant Console API。规划容量为 20 个 Tenant、10,000 名活跃用户、1,000 峰值并发用户、100 RPS 基线、200 RPS 突发余量以及 100,000 条审计事件/日（2 倍增长余量）。实际 Pod 资源请求和副本上限须由该压测基线的报告确定，不在文档中虚构固定规格。

## 发布、回滚与变更审计

- GitHub Actions 执行测试、契约、覆盖率、镜像与漏洞扫描、ZAP 基线扫描和 Helm 验证。
- `master` 必须经 Pull Request 并通过所有自动门禁；单人开发阶段不强制独立批准，团队增加第二位开发者后要求至少一名独立审查者批准。
- 受保护的 `vX.Y.Z` 标签在 JDK 17/21门禁通过后，由 JDK 17向 Maven Central 发布签名的 SDK、Starter 与 BOM；Maven 发布约定见 [Maven 构建与制品发布](21-maven-build-and-release.md)。镜像与 Helm Chart 仍由各自发布流程处理。每次部署记录版本、迁移、配置版本、操作者、开始/完成时间和回滚结果。
- Flyway 迁移随服务版本发布。生产变更先在等效环境验证；失败时回滚应用版本，数据库迁移按事先验证的前向修复或可逆方案处理。

## 虚拟机裸部署

虚拟机方案以四个独立服务、Gateway、Console 静态资源和受管外部依赖组成。每个服务由独立 `systemd` 单元运行，配置健康检查、受限账号、凭据文件、日志转发和自动重启；不得把所有服务、数据库和 Kafka 压缩为无隔离的单一进程。
