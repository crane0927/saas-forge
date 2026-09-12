# Entitlement 原生联调

本说明对应 Issue #165，接续 [Tenant Access 与 IAM](native-tenant-access-development.md)。浏览器仍经受信 HTTPS、Console 和 Gateway，跨服务使用 Nacos 服务发现。

## 个人配置与独立准备

复制 `services/entitlement-service/src/main/resources/application-local.yaml.example` 为同目录 `application-local.yaml`；已有个人文件不覆盖。IDE 激活 `local` 后从 classpath 加载，无需 Nacos 配置中心。个人文件被 Git 忽略，个人文件和模板都排除在发布 JAR 外。

模板可配置 PostgreSQL、Redis 和 Nacos 地址，默认分别为 `127.0.0.1:5432/6379/8848`，数据库与应用账号为 `entitlement_db`、`entitlement_app`。数据库迁移、保留 Service Client 与依赖初始化独立完成，应用不获得迁移账号或管理员凭据。IAM、Tenant Access 所需 Kafka 和邮件等依赖沿用各自说明。

通过 IDE 环境变量或权限受限的外部 Spring configtree 注入以下配置；敏感值不粘贴到个人 YAML：

| 配置 | 用途 |
| --- | --- |
| `NACOS_ENTITLEMENT_USERNAME` / `NACOS_ENTITLEMENT_PASSWORD` | Entitlement 专属 Nacos 工作负载身份 |
| `ENTITLEMENT_APP_PASSWORD` | 数据库应用账号密码 |
| `REDIS_PASSWORD` | 撤销索引 Redis 凭据 |
| `IAM_JWT_ISSUER` | 与 IAM、Gateway 一致的 issuer |
| `SAASFORGE_SERVICE_CLIENT_ID_FILE` / `SAASFORGE_SERVICE_CLIENT_SECRET_FILE` | Entitlement 自己的保留 Service Client 文件 |

Secret 路径为绝对路径，文件建议 600、父目录 700。使用已初始化的服务身份，不重置或替换 Client。IAM 与 Tenant Access 的注册 metadata 须包含自身 `grpc.port: ${spring.grpc.server.port}`；升级已有个人配置时也要检查。

已有 dev 环境由管理员独立增加以下 naming 读取权限，不重跑全部初始化：

| 角色 | 资源 | action |
| --- | --- | --- |
| `entitlement-service-dev` | `dev:DEFAULT_GROUP:naming/iam-service` | `r` |
| `entitlement-service-dev` | `dev:DEFAULT_GROUP:naming/tenant-access-service` | `r` |
| `tenant-access-service-dev` | `dev:DEFAULT_GROUP:naming/entitlement-service` | `r` |

非 dev 环境使用实际角色及 namespace。既有 IAM ↔ Tenant Access 权限继续保留，不增加跨应用配置读取或注册权限。新 dev 环境初始化脚本已包含上述权限，`scripts/verify-nacos-acl.sh` 检查对应读取与配置隔离。脚本通过不等于业务通信成功。

## 更新代码后的数据库迁移

原生应用使用应用账号，启动或重启 IDE 服务不会自动执行数据库迁移。更新代码后，先用迁移账号检查并应用新增迁移，再验证新接口。已有环境升级 #173 时应从 V4 前进到 V5，新增 `quota_definition_recovery` 表；已有额度定义不会因此生成历史恢复记录。

升级 #174 时继续前进到 V6，新增 `plan_recovery` 并导入已有 Plan 稳定响应及原保留期。历史零额度保持可读且不改变，新的创建/激活/订阅要求上限至少 1。请先完成独立迁移，再使用新版 Plan 页面与恢复接口。

若使用仓库既有 Compose PostgreSQL 和已配置的受限凭据，可独立运行迁移容器：

```bash
cd deploy/compose
docker compose run --rm --no-deps entitlement-migrate info
docker compose run --rm --no-deps entitlement-migrate migrate
```

该命令只执行 Entitlement 的 Flyway 迁移，不启停 IDE 应用，也不替换 HTTPS Edge。外部数据库使用对应环境的受控 Flyway 发布流程，不把 Compose 设为必经步骤。若历史校验失败，先按迁移不可变规则调查，不使用 repair 或重建数据绕过。

## IDEA Run / Debug / 重启

重新同步 Maven，在 IDEA 创建 Spring Boot 配置：主类 `io.saasforge.entitlement.EntitlementServiceApplication`，classpath `entitlement-service`，Active profiles `local`。只保留 IDE Build，不添加 package、后台 JAR 或 replace/restore 前置步骤。

HTTP 默认 8083、gRPC 默认 9093。`ENTITLEMENT_HTTP_PORT`、`ENTITLEMENT_GRPC_PORT`、`ENTITLEMENT_REGISTER_IP`、`ENTITLEMENT_BIND_ADDRESS` 控制自身端口、注册 IP 和 HTTP 监听地址。HTTP 目标取 Nacos IP/port，gRPC 目标取同一实例 IP/`grpc.port`，不设置下游实例地址。

同时在 IDEA 运行 Gateway、IAM、Tenant Access、Entitlement；开发者自行处理重复实例和注册地址可达性。修改后使用 IDEA Build、Stop、Debug。可在 Entitlement `GrpcTenantEligibilityGateway.checkInitialSubscription` 或 Quota 接收入口设断点，恢复执行后确认正式操作结果。

local 下每次调用查询健康实例，HTTP/gRPC 不回退固定容器地址；空列表、发现异常或非法 gRPC metadata 明确失败。内部 local gRPC 沿用明文与 3 秒默认调用上限，浏览器 HTTPS 及非 local 的通道/TLS 配置不变。

## 真实业务验收

使用已有平台管理员和正式 Console/shared typed client operation，不自行注入 Cookie、Origin、Fetch Metadata 或 Bearer Token。复用 `scripts/verify-tenant-lifecycle-e2e.sh` 的正式业务步骤；该脚本本身会创建完整 Compose 环境，不作为日常启动入口。

1. 复用已启用的 `max_users` Quota Definition 和合适 Plan；新增时使用正式平台操作与独立幂等键，记录资源 ID。
2. 创建专用验收 Tenant，确认 `PENDING`；创建首个 Subscription，确认 `ACTIVE`。这覆盖 Entitlement → IAM 服务 Token/Platform Role 与 Entitlement → Tenant Access 资格查询。
3. 初始化管理员，确认 Tenant 为 `ACTIVE`、初始管理员 Membership 启用并具有 Tenant Administrator Role。这覆盖 Tenant Access → IAM Identity/Password Setup 和 Tenant Access → Entitlement Quota。
4. 以既有验收的只读数据库观测或正式 Quota 操作确认 `max_users` 实际占用一个名额，重试原初始化不重复扣减；记录观测方法与结果，不以服务注册代替副作用证据。
5. Entitlement 改为 HTTP 8183/gRPC 9193 后在 IDEA 重启，调用方配置不改，以新验收 Tenant 重复业务步骤；恢复端口后复查。
6. 停止 Entitlement 并确认无健康实例，再执行需要权益的正式操作，记录拒绝或初始化待重试结果。恢复后沿原幂等操作安全重试，不重置数据。不随意中断共享 Nacos，自动化发现异常和现场故障证据分别记录。

保存 IDEA Run/Debug、断点、重启、端口变化、真实业务与 Quota 观测记录。自动化不能替代现场证据。

## 验证范围

聚焦配置及通信回归：

```bash
mvn -pl services/entitlement-service,services/tenant-access-service -am \
  -Dtest=LocalConfigurationTest,LocalServiceDiscoveryTest,NacosServiceEndpointsTest,DiscoveredGrpcChannelTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

相关模块完整检查：

```bash
mvn -pl gateway,services/iam-service,services/tenant-access-service,services/entitlement-service -am verify
```

仓库完整 CI、Fresh Compose 和 Chrome 产品验收独立执行与记录。当前结果见 [Issue #165 验收记录](acceptance/issue-165-native-entitlement.md)。
