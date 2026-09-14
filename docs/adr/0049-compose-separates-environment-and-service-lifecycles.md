---
status: accepted
---

# Compose 按运行环境与服务拆分生命周期

现有 Compose 将基础设施、应用与一次性任务放在同一项目中。已确认的设计方向是让运行环境与每个应用分别独立启停，同时将应用的 Compose 定义放回所属目录；仅拆分文件但继续绑定同一项目生命周期不能满足本次目标。用户已确认共享网络、按目录配置及独立验收组合方案。设计确认不代表运行验收完成。

## 已确认的归属

- `deploy/compose` 保留 PostgreSQL、Redis、Kafka、Mailpit、OTel Collector、Nacos、Nacos 初始化及共享 HTTPS 入口。
- Gateway 的 Compose 放在 `gateway`；IAM、Tenant Access、Entitlement、Audit 的 Compose 放在各服务根目录。
- Platform Console、Tenant Console 的 Compose 分别放在对应 Console 目录。
- 四个业务服务的数据库迁移任务随所属服务迁移，继续作为对应容器应用的启动门禁；PostgreSQL 集群引导仍归运行环境。
- IAM 的管理员初始化、管理员密码重置、服务客户端初始化和替换任务归 IAM；审计隔离消息重放任务归 Audit。这些按需任务保持显式执行，普通应用启动不会触发。

## 配套决策与约束

- 保持 ADR 0019、0020 的集群引导、服务迁移与常驻应用权限边界，以及 ADR 0043 的原生开发和集成验收分工。
- 独立启停需要重新处理现有跨服务、跨基础设施的 `depends_on`；不能仅移动 YAML 文件。
- 运行环境拥有共享 Docker 网络和原有数据卷；应用以 external 网络接入。先准备环境，应用启动不自动拉起其他应用。
- 各目录提供 `.env.example`，实际受限凭据文件可被引用，不复制实际凭据；跨目录使用绝对路径以免解析歧义。
- `deploy/docker` 保存共用 Dockerfile；`deploy/acceptance` 通过 Compose `extends` 复用服务定义，恢复完整验收依赖并使用独立网络与数据卷。
- 原统一项目中的应用容器须由开发者显式停止后再切换独立项目，保留环境项目名与数据卷；文件重组不自动迁移运行实例。
- 配置拆分不授权停止现有用户管理的进程、删除数据卷或执行凭据重置等维护任务。
