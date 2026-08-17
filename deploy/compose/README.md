# Docker Compose

Compose 用于本地开发、演示和端到端测试。

当前编排提供 PostgreSQL 18 集群引导和 IAM、Tenant Access、Entitlement、Audit 的独立 Flyway 迁移任务，不包含尚未实现的领域服务镜像。

## 启动

在本目录准备本地凭据并启动：

```bash
cp .env.example .env
# 将 .env 中的 replace-with-a-secret 替换为本地随机密码
docker compose up -d
```

`.env` 是本地文件，已被 Git 忽略。它包含 9 个密码：一个集群引导管理员密码和四个服务各自的 migrator、app 账号密码。八个服务账号名由 `bootstrap.sh` 固定创建，密码只保存在 `.env`。

## PostgreSQL 集群引导

`../postgresql/bootstrap.sh` 只在 `postgres-data` 首次创建时执行。它创建四个独立数据库（`iam_db`、`tenant_access_db`、`entitlement_db`、`audit_db`）和对应账号，撤销 `PUBLIC` 默认权限，并只授予 migrator 账号创建 schema 对象的权限。

脚本必须保留可执行权限。若 PostgreSQL 日志出现 `/usr/bin/env: bad interpreter: Permission denied`，在仓库根目录执行：

```bash
chmod +x deploy/postgresql/bootstrap.sh
```

随后必须重新初始化。仅在确认没有需要保留的本地数据时执行以下命令；它会删除 `postgres-data` volume：

```bash
cd deploy/compose
docker compose down -v
docker compose up -d
```

生产环境由数据库运行方执行等价的集群引导流程，不应通过删除数据卷重新初始化。

## Flyway 迁移任务

四个 `*-migrate` 容器是一次性任务，分别迁移自己的数据库：

| 容器 | 数据库 |
| --- | --- |
| `iam-migrate` | `iam_db` |
| `tenant-access-migrate` | `tenant_access_db` |
| `entitlement-migrate` | `entitlement_db` |
| `audit-migrate` | `audit_db` |

PostgreSQL healthy 后，它们以各自的 `*_migrator` 账号读取服务目录下的 `db/migration` 并执行未应用的迁移。首次初始化、新增迁移后，以及测试、预发或生产发布前应运行它们。`Exited (0)` 表示任务成功完成；日常使用不需要保持运行。再次运行且没有新迁移时，Flyway 只校验历史记录后退出。

## Navicat 连接

PostgreSQL 仅发布到本机 `127.0.0.1:5432`，不会暴露到局域网。Navicat 可使用以下参数连接：

- 主机：`127.0.0.1`
- 端口：`5432`
- 数据库：四个服务数据库之一，例如 `iam_db`
- 用户：对应服务的 `*_app` 或 `*_migrator` 账号，例如 `iam_app`
- 密码：`.env` 中相应的密码变量
