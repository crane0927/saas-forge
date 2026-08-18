# 部署交付

本目录提供本地 Compose，并预留生产 Helm 与虚拟机 systemd 交付物。部署拓扑和安全约束见 docs/14-deployment.md。

## 最小本地运行拓扑

最小 Compose 包含 Gateway、IAM、Tenant Access、Entitlement、Audit、PostgreSQL、Redis、单节点 KRaft Kafka、OpenTelemetry Collector 与四个 Flyway 一次性迁移任务。S3 兼容对象存储将在第 6 阶段加入。

从 `compose/` 目录执行：

    test -f .env || cp .env.example .env
    # 为 .env 中每个变量填写仅用于本地开发的值
    docker compose config
    docker compose up --build

所有端口只绑定至 `127.0.0.1`：Gateway `8080`；IAM、Tenant Access、Entitlement、Audit 分别为 `8081` 至 `8084`；PostgreSQL `5432`；Redis `6379`；Kafka `29092`；OTLP gRPC/HTTP 为 `4317`/`4318`。

PostgreSQL、Redis 和 Kafka 使用独立命名卷。需要清空本地基础设施数据时，显式执行：

    docker compose down -v

该命令会删除这三个服务的本地数据卷。Collector 使用 debug exporter，仅把收到的遥测输出到自身标准输出；本阶段不部署 Prometheus、Loki、Tempo 或 Grafana。
