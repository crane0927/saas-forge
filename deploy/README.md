# 部署交付

本目录保存 saas-forge 的部署交付物：本地 Docker Compose 运行环境、Nacos 配置清单、生产 Helm 接入契约和虚拟机 `systemd` 预留目录。部署拓扑与安全约束见 [部署设计](../docs/14-deployment.md)。

## 选择交付物

| 场景 | 使用的目录或脚本 | 说明 |
| --- | --- | --- |
| 本地开发、演示或端到端测试 | [`compose/`](compose/README.md) | 启动 Gateway、四个领域服务及本地依赖；不用于生产。 |
| 修改、校验或发布 Nacos 非敏感配置 | [`nacos/`](nacos/README.md) 与仓库根目录的 `scripts/*nacos*.sh` | 配置清单按环境受 Git 管理；生产发布只能由受保护的 GitHub Actions 工作流执行。 |
| 生产 Kubernetes 接入外部 Nacos | [`helm/`](helm/README.md) | 仅提供应用 Chart 与外部 Nacos 的接口契约，不包含完整应用 Chart 或 Nacos Server。 |
| 虚拟机裸部署 | [`systemd/`](systemd/README.md) | 预留独立服务单元与受限账号配置示例。 |

## 本地 Compose

从 `deploy/compose/` 执行。首次使用先根据 [`.env.example`](compose/.env.example) 创建 `.env`，并填写全部仅限本地使用的变量：

```bash
cd deploy/compose
test -f .env || cp .env.example .env
docker compose config
docker compose up --build
```

`docker compose up --build` 会构建五个应用镜像，并按依赖顺序启动 Nacos 初始化、PostgreSQL 初始化、Flyway 迁移和应用服务。状态与日志可用以下命令查看：

```bash
docker compose ps --all
docker compose logs nacos-init
docker compose logs postgres
```

日常停止使用 `docker compose down`。只有需要重新初始化 PostgreSQL、Redis 和 Kafka 的本地数据时，才使用 `docker compose down -v`；该命令会删除这三个命名卷。

所有宿主机端口仅绑定 `127.0.0.1`。完整组件、端口、环境变量和故障说明见 [`compose/README.md`](compose/README.md)。

### Compose 内部脚本

下列脚本由 Compose 容器自动执行，依赖容器内的网络、命令和环境变量，不作为宿主机上的独立部署命令使用。

| 脚本 | 功能 | 使用方式 | 适用场景 |
| --- | --- | --- | --- |
| [`compose/nacos-init.sh`](compose/nacos-init.sh) | 在 Nacos 健康后初始化管理员密码，校验六个非管理员身份互不重复，创建 `dev` namespace、角色和最小权限；随后用配置发布身份写入五份 `SAAS_FORGE` 配置。再次运行会将这些身份密码更新为 `.env` 中的值。 | `docker compose up --build` 自动执行；本地 Nacos 已启动时，执行 `docker compose run --rm nacos-init` 可重新发布 `dev` 配置并更新声明的身份密码。 | 首次启动本地环境；更新 Git 管理的 `dev` 配置，或轮换本地 Nacos 开发身份密码后重新初始化。 |
| [`postgresql/bootstrap.sh`](postgresql/bootstrap.sh) | 校验四个领域服务的 migrator/app 密码，创建各自数据库与受限账号，并授予 migrator 建表权限和 app 连接/使用 schema 权限。 | 由 PostgreSQL 官方镜像在**首次创建** `postgres-data` 卷时自动执行。要重新执行，须先确认可丢弃本地数据，再运行 `docker compose down -v` 后重新启动。 | 新建本地数据库卷；需要从零开始重建本地数据库与服务账号。 |

`compose/Dockerfile` 是 Compose 构建本地应用镜像的共用 Dockerfile：它按传入的 `MODULE` 构建对应 Maven 模块，并以非 root 的 `spring` 用户运行 JAR。通常无需手动调用，由 `docker compose up --build` 使用。

### Nacos 故障恢复验收

在已填写 `deploy/compose/.env` 后，从仓库根目录运行：

```bash
bash scripts/verify-nacos-failure-recovery.sh
```

脚本通过 [`compose/failure-recovery.override.yaml`](compose/failure-recovery.override.yaml) 创建独立 Compose 项目，不占用或停止正在运行的开发栈。它验证：没有健康 IAM 实例时 Gateway 返回 `503`；Nacos 短暂不可用时已启动的 Gateway 继续使用已知健康实例；Nacos 控制面不可用时新的 IAM 实例不能成功启动。退出时会清理该验收项目创建的容器和卷。

## Nacos 配置与相关脚本

[`nacos/`](nacos/README.md) 中的 `dev`、`test`、`staging`、`prod` 目录是 Nacos 非敏感配置的权威来源。每个环境必须且只能包含 Gateway、IAM、Tenant Access、Entitlement、Audit 五份应用专属配置。不要将密码、令牌、凭据或 Nacos 连接参数写入这些清单。

| 脚本 | 功能与使用方式 | 适用场景 |
| --- | --- | --- |
| [`scripts/validate-nacos-config.sh`](../scripts/validate-nacos-config.sh) | `bash scripts/validate-nacos-config.sh [dev|test|staging|prod]`；不传参数时校验全部四个环境的资源完整性、YAML 结构、版本标记和敏感配置禁令。 | 修改 Nacos 清单后的本地校验；`Verify` CI 门禁。 |
| [`scripts/publish-nacos-config.sh`](../scripts/publish-nacos-config.sh) | `bash scripts/publish-nacos-config.sh <environment>`；要求设置 `NACOS_SERVER_ADDR`、`NACOS_PUBLISH_USERNAME`、`NACOS_PUBLISH_PASSWORD`，并在发布前重跑该环境校验。 | GitHub Actions 的 `Publish Nacos configuration` 工作流入口。生产发布须选择受保护的目标 Environment；回滚时选定已验证提交重新发布，不以 Console 修改替代。 |
| [`scripts/verify-nacos-acl.sh`](../scripts/verify-nacos-acl.sh) | 需要设置目标地址、namespace（默认 `dev`）及五个工作负载身份的用户名和密码；逐一验证每个身份可读自身配置，且不能读取其他配置或发布配置。 | 在临时或本地 Nacos 完成初始化、发布后，验证最小权限边界。 |
| [`scripts/validate-nacos-production-contract.sh`](../scripts/validate-nacos-production-contract.sh) | `bash scripts/validate-nacos-production-contract.sh`；校验生产 Nacos HTTPS/TLS、`prod` namespace、五个互不共享的外部 Secret，以及五个应用的 Config TLS 开关。 | 修改 Helm Nacos 接入契约或应用 Nacos TLS 入口后的本地校验与 CI 门禁。 |

生产 Nacos 接入需要满足 [`helm/nacos-production-contract.yaml`](helm/nacos-production-contract.yaml) 和 [`helm/README.md`](helm/README.md) 的约束：使用外部高可用 Nacos HTTPS 端点，工作负载凭据只从外部 Secret 引用，应用 Chart 不部署 Nacos Server。

## 变更前后检查

- 修改 `deploy/nacos/<environment>/` 中的清单后，运行对应的 `validate-nacos-config.sh` 校验；发布由 GitHub Actions 工作流完成。
- 修改 `helm/nacos-production-contract.yaml` 或应用的 Nacos TLS 配置入口后，运行 `validate-nacos-production-contract.sh`。
- 修改 Compose 编排、初始化脚本或服务发现恢复行为后，在具备 Docker 环境且已准备本地 `.env` 的前提下，运行 `verify-nacos-failure-recovery.sh`。
- `.env` 仅限本地，已被 Git 忽略；不得提交，也不得使用生产凭据。
