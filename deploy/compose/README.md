# 最小本地 Docker Compose

[English](README-en.md)

本目录提供 saas-forge 的最小本地运行拓扑，供开发、演示和端到端测试使用。

## 包含内容

- Gateway；IAM、Tenant Access、Entitlement、Audit 四个领域服务
- PostgreSQL 18，以及四个服务各自的一次性 Flyway 迁移任务
- Redis、单节点 KRaft Kafka、单节点 Nacos 与 OpenTelemetry Collector
- PostgreSQL、Redis、Kafka 的独立命名卷

S3 兼容对象存储不属于当前拓扑，将在第 6 阶段加入。当前 Collector 仅通过 `debug` exporter 输出遥测数据，不部署 Prometheus、Loki、Tempo 或 Grafana。

## 启动

在本目录执行：

```bash
test -f .env || cp .env.example .env
# 为 .env 中的全部变量填写仅用于本地开发的值
bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose config
docker compose up --build
```

初始化脚本会在 `.secrets/` 中生成 Git 忽略的 PKCS#8 RSA 私钥，执行 IAM Flyway 迁移，并在数据库尚无 ACTIVE Signing Key 时写入与该私钥公钥一致的本地元数据。脚本可重复执行；若数据库已有不匹配的 ACTIVE Key，则拒绝覆盖并要求显式轮换。

首次启动时，Nacos 会用 `.env` 中的显式管理员密码完成首次初始化，并由 `nacos-init` 创建非默认的 IAM、Tenant Access、Entitlement、Audit、Gateway 开发身份、配置发布身份、`dev` namespace，以及 `SAAS_FORGE` group 中各自的配置资源；PostgreSQL healthy 后四个 `*-migrate` 任务会完成各自数据库迁移；对应领域服务随后启动，Gateway 最后启动。Compose 明确向所有应用传入 `NACOS_TLS_ENABLED=false`，因为它只提供隔离网络内的单节点开发 Nacos；不得将此拓扑、地址或凭据复制到生产。五个应用均以 `refreshEnabled=false` 导入自身配置资源，常规配置变更由受控发布流程配合滚动发布生效；当前没有可在本地热更新的策略。任一服务的 Nacos 配置不存在、Nacos 不可达或注册失败时不会 Ready；Gateway 仅通过 Nacos 的健康实例代理当前公开路由所属服务，Audit 注册不会开放新入口。Nacos 本地控制台访问 <http://127.0.0.1:8849/>。可用以下命令查看状态：

```bash
docker compose ps --all
```

`*-migrate` 显示 `Exited (0)` 表示迁移成功。当前服务尚未提供业务路由，因此直接请求服务根路径返回 `404` 是预期行为。

> [!IMPORTANT]
> `.env` 仅限本地使用，已被 Git 忽略。必须填写一个 PostgreSQL 管理员用户名及全部必填变量；不要提交 `.env`，也不要将本地短码用于任何非本地环境。

## 本地端口

所有宿主机端口均只绑定到 `127.0.0.1`，不会暴露到局域网。

| 组件 | 本地端口 | 说明 |
| --- | ---: | --- |
| Gateway | 8080 | HTTP |
| IAM | 8081 | HTTP |
| Tenant Access | 8082 | HTTP |
| Entitlement | 8083 | HTTP |
| Audit | 8084 | HTTP |
| PostgreSQL | 5432 | 数据库连接 |
| Redis | 6379 | 需使用 `REDIS_PASSWORD` 认证 |
| Kafka | 29092 | 主机外部监听；容器内服务使用 `kafka:9092` |
| Nacos | 8848 / 8849 | 配置与服务发现 API / 本地控制台；仅限本地开发 |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP |

## 环境变量

`.env.example` 包含所需变量名，不提供默认密码。`POSTGRES_ADMIN_USER` 是 PostgreSQL 初始化管理员账号；JWT issuer、Key Version 引用和本地私钥路径提供安全边界内的开发默认值，其余变量均为密码或 Nacos 认证材料：

| 服务 | migrator 密码 | app 密码 |
| --- | --- | --- |
| PostgreSQL 集群引导 | `POSTGRES_ADMIN_PASSWORD` | — |
| IAM | `IAM_MIGRATOR_PASSWORD` | `IAM_APP_PASSWORD` |
| Tenant Access | `TENANT_ACCESS_MIGRATOR_PASSWORD` | `TENANT_ACCESS_APP_PASSWORD` |
| Entitlement | `ENTITLEMENT_MIGRATOR_PASSWORD` | `ENTITLEMENT_APP_PASSWORD` |
| Audit | `AUDIT_MIGRATOR_PASSWORD` | `AUDIT_APP_PASSWORD` |
| Redis | `REDIS_PASSWORD` | — |
| Nacos | `NACOS_BOOTSTRAP_PASSWORD` | `NACOS_PUBLISH_PASSWORD`、`NACOS_IAM_PASSWORD`、`NACOS_TENANT_ACCESS_PASSWORD`、`NACOS_ENTITLEMENT_PASSWORD`、`NACOS_AUDIT_PASSWORD`、`NACOS_GATEWAY_PASSWORD` |

`NACOS_IAM_USERNAME`、`NACOS_TENANT_ACCESS_USERNAME`、`NACOS_ENTITLEMENT_USERNAME`、`NACOS_AUDIT_USERNAME` 与 `NACOS_GATEWAY_USERNAME` 必须是非默认开发身份。`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 与 `NACOS_AUTH_TOKEN` 均须填写仅用于本地的随机值；`NACOS_AUTH_TOKEN` 必须是由至少 32 个原始字符生成的 Base64 字符串。`nacos-init` 仅用初始化管理员身份创建 namespace、用户和权限，随后改用仅可写入五个受控配置资源的 `NACOS_PUBLISH_USERNAME` 发布清单；每个领域服务身份仅被授予读取自己的配置和注册自己的稳定服务名，Gateway 身份仅被授予读取自己的配置、注册 `gateway` 与读取 `iam-service`、`tenant-access-service`、`entitlement-service` 健康实例的权限。完整清单、CI 发布和应急回写流程见 [`../nacos/README.md`](../nacos/README.md)。

`bootstrap.sh` 在首次创建 PostgreSQL 数据卷时建立 `iam_db`、`tenant_access_db`、`entitlement_db`、`audit_db`，以及各服务独立的 `*_migrator` 和 `*_app` 账号。迁移任务使用 migrator 账号，运行时服务使用 app 账号。

## Nacos 故障恢复验收

在已准备好本地 `.env` 后，从仓库根目录运行：

```bash
bash scripts/verify-nacos-failure-recovery.sh
```

该脚本使用独立 Compose 项目和 `failure-recovery.override.yaml`，不会占用或停止开发栈的宿主机端口和容器。它依次验证 Gateway 无健康 IAM 实例时返回 `503` 且没有静态地址回退、Nacos 短暂停止后已启动 Gateway 继续使用已知健康实例，以及控制面不可用时新的 IAM 实例无法因缺少必需配置而启动。退出时只删除该独立验收项目创建的容器和卷。

## 停止与重置

日常停止环境：

```bash
docker compose down
```

需要彻底重置 PostgreSQL、Redis 与 Kafka 的本地数据时：

```bash
docker compose down -v
```

> [!CAUTION]
> `down -v` 会删除此 Compose 项目的三个命名数据卷。仅在确认其中没有需要保留的本地数据时使用。
