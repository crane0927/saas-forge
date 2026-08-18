# 最小本地 Docker Compose

[English](README-en.md)

本目录提供 saas-forge 的最小本地运行拓扑，供开发、演示和端到端测试使用。

## 包含内容

- Gateway；IAM、Tenant Access、Entitlement、Audit 四个领域服务
- PostgreSQL 18，以及四个服务各自的一次性 Flyway 迁移任务
- Redis、单节点 KRaft Kafka 与 OpenTelemetry Collector
- PostgreSQL、Redis、Kafka 的独立命名卷

S3 兼容对象存储不属于当前拓扑，将在第 6 阶段加入。当前 Collector 仅通过 `debug` exporter 输出遥测数据，不部署 Prometheus、Loki、Tempo 或 Grafana。

## 启动

在本目录执行：

```bash
test -f .env || cp .env.example .env
# 为 .env 中的全部变量填写仅用于本地开发的值
docker compose config
docker compose up --build
```

首次启动时，PostgreSQL healthy 后四个 `*-migrate` 任务会完成各自数据库迁移；对应领域服务随后启动，Gateway 最后启动。可用以下命令查看状态：

```bash
docker compose ps --all
```

`*-migrate` 显示 `Exited (0)` 表示迁移成功。当前服务尚未提供业务路由，因此直接请求服务根路径返回 `404` 是预期行为。

> [!IMPORTANT]
> `.env` 仅限本地使用，已被 Git 忽略。必须填写一个 PostgreSQL 管理员用户名及全部 10 个密码变量；不要提交 `.env`，也不要将本地短码用于任何非本地环境。

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
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP |

## 环境变量

`.env.example` 包含所需变量名，不提供默认密码。`POSTGRES_ADMIN_USER` 是 PostgreSQL 初始化管理员账号；其余变量均为密码：

| 服务 | migrator 密码 | app 密码 |
| --- | --- | --- |
| PostgreSQL 集群引导 | `POSTGRES_ADMIN_PASSWORD` | — |
| IAM | `IAM_MIGRATOR_PASSWORD` | `IAM_APP_PASSWORD` |
| Tenant Access | `TENANT_ACCESS_MIGRATOR_PASSWORD` | `TENANT_ACCESS_APP_PASSWORD` |
| Entitlement | `ENTITLEMENT_MIGRATOR_PASSWORD` | `ENTITLEMENT_APP_PASSWORD` |
| Audit | `AUDIT_MIGRATOR_PASSWORD` | `AUDIT_APP_PASSWORD` |
| Redis | `REDIS_PASSWORD` | — |

`bootstrap.sh` 在首次创建 PostgreSQL 数据卷时建立 `iam_db`、`tenant_access_db`、`entitlement_db`、`audit_db`，以及各服务独立的 `*_migrator` 和 `*_app` 账号。迁移任务使用 migrator 账号，运行时服务使用 app 账号。

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
