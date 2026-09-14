# 部署交付

本目录保存 saas-forge 的部署交付物：本地 Docker Compose 运行环境、Nacos 配置清单、生产 Helm 接入契约和虚拟机 `systemd` 预留目录。部署拓扑与安全约束见 [部署设计](../docs/14-deployment.md)。

日常应用开发从[原生开发总入口](../docs/native-local-development.md)开始；本目录的完整 Compose 用于演示、集成验收或专项复现。

## 目录与使用入口

| 用途 | 入口 |
| --- | --- |
| 基础设施与共享 HTTPS | [`compose/`](compose/README.md) |
| 应用独立启停 | 各服务、Gateway 与 Console 目录下的 `compose.yaml` |
| 完整集成验收及场景覆盖 | [`acceptance/`](acceptance/README.md) |
| 共用后端构建与静态托管文件 | `docker/` |
| Nacos 环境资源 | [`nacos/`](nacos/README.md) |
| 生产 Kubernetes 配置 | [`helm/`](helm/README.md) |

日常依赖从 `deploy/compose` 启动；应用分别连接共享网络，各自管理迁移任务。默认环境项目名与数据卷名保持不变。完整验收复用服务定义，在自己的项目中恢复跨服务启动门禁并隔离网络、数据卷。具体命令、凭据准备和旧项目迁移见对应入口。

PostgreSQL 集群引导仍由 `postgresql/bootstrap.sh` 负责，服务 Flyway 迁移归各服务。`compose/nacos-init.sh` 仍负责运行环境的身份、权限和配置准备。共享 Dockerfile 已移至 `docker/Dockerfile` 与 `docker/Dockerfile.prebuilt`。

运行 `python3 scripts/validate-compose-layout.py` 可在不启动容器的情况下检查拆分边界及验收组合。Nacos 故障恢复继续使用 `bash scripts/verify-nacos-failure-recovery.sh`，需要准备 `deploy/acceptance/.env` 或由 CI 提供相应环境变量。

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
