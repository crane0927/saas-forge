# 开发配置来源与 IDE 启动设置

## 默认从 Nacos 读取

五个后端直接在 IDE Run/Debug，Active profiles 和 Program arguments 留空。默认从当前开发 Nacos 的 `dev` namespace、`SAAS_FORGE` group 读取各自的 `<application>.yaml`，并通过 Nacos 发现其他服务。无需 Maven Profile 或 `--spring.profiles.active=local`。

- 业务策略、认证期限、重试和就绪检查：`deploy/nacos/dev/` 清单，经 `nacos-init` 同步到当前开发 Nacos。
- Nacos、数据库、Redis、Kafka 的连接参数，本实例监听和注册参数：各应用 `application.yaml`，通过环境变量覆盖环境差异。
- 密码、私钥和服务身份：应用专属环境变量或受限 `configtree` 目录；目录权限 700、文件 600。YAML 只保留占位符，不导入整份 Compose 管理员环境。

不再保留实际 `application-local.yaml`、对应模板或 IDE 中的大段 `SPRING_APPLICATION_JSON`。`application.yaml` 是应用基础配置，继续保留。

## IDE 直接启动

五服务的运行配置统一使用仓库根目录作为 Working directory（IDEA 中为 `$PROJECT_DIR$`）。Active profiles、Program arguments、Environment variables 均无需填写；删除旧的 `SPRING_APPLICATION_JSON`、凭据导入及本机端口覆盖变量。保留正确的启动类、模块 classpath 和 JDK 17，Build 后直接 Run/Debug。

各服务的 `application.yaml` 默认导入 `configtree:./deploy/compose/.secrets/native-local/<component>/`。目录相对于进程工作目录，必须以 `/` 结尾；不是相对于 YAML 所在目录。每个文件名是对应属性名，例如 `NACOS_IAM_PASSWORD`。实际凭据文件继续由开发者维护并受 Git 忽略，目录权限 700、文件 600，不提交密码或个人绝对路径。默认目录不存在时启动失败。

| 服务 | HTTP | gRPC | 凭据目录（相对仓库根目录） |
| --- | --- | --- | --- |
| Gateway | 8080 | 无 | `deploy/compose/.secrets/native-local/gateway/` |
| IAM | 8081 | 9091 | `deploy/compose/.secrets/native-local/iam/` |
| Tenant Access | 8082 | 9092 | `deploy/compose/.secrets/native-local/tenant-access/` |
| Entitlement | 8083 | 9093 | `deploy/compose/.secrets/native-local/entitlement/` |
| Audit | 8084 | 无 | `deploy/compose/.secrets/native-local/audit/` |

默认注册 IP 为 `127.0.0.1`。除 Gateway 外，HTTP 默认监听 `127.0.0.1`；Gateway 保留 `0.0.0.0`，供既有 HTTPS Edge 访问。Nacos 为 `127.0.0.1:8848`、namespace 为 `dev`；PostgreSQL 为 `127.0.0.1:5432`、使用各服务独立数据库及应用账号；Redis 为 `127.0.0.1:6379`；Kafka 为 `127.0.0.1:29092`。

当前开发环境直接使用这些默认值。确需其他环境时，仍可通过 YAML 中保留的环境变量覆盖，例如 `NACOS_SERVER_ADDR`、`TENANT_ACCESS_HTTP_PORT`、`TENANT_ACCESS_GRPC_PORT`、`TENANT_ACCESS_REGISTER_IP`、`KAFKA_BOOTSTRAP_SERVERS`。凭据目录变更可覆盖 `SAASFORGE_SECRETS_IMPORT=configtree:/absolute/path/to/service-secrets/`；由部署环境直接注入凭据时，将该值设为空，避免读取开发者目录。

Compose 已显式覆盖容器差异：HTTP 监听 `0.0.0.0:8080`，gRPC 使用 `9090`，注册 IP 留空由客户端选择容器地址，Kafka 使用 `kafka:9092`，并清空 `SAASFORGE_SECRETS_IMPORT`。容器继续通过原有环境变量和 Secret 挂载接收凭据。本次没有发布或启动容器；其他部署入口也必须显式提供符合其环境的端口、地址和凭据方式，不直接采用开发默认值。

HTTP 注册端口引用 `${server.port}`，gRPC metadata 引用 `${spring.grpc.server.port}`，修改实例监听端口会同步到注册信息。这里配置的是当前实例，不提供下游静态地址。

`application.yaml` 导入凭据及 Nacos；Nacos 导入保持必需、`refreshEnabled=false`。内部监听地址不替代受信 HTTPS 浏览器入口。IDE 运行配置和 YAML 修改需 Build、重启应用后生效。

## 服务之间如何调用

IAM、Tenant Access、Entitlement 的内部 gRPC，以及 Tenant Access/Entitlement 访问 IAM 的 HTTP，都按服务名查询 Nacos 健康实例。HTTP 使用注册 IP/port，gRPC 使用注册 IP 与 `grpc.port` metadata；应用默认以 `${spring.grpc.server.port}` 登记自身 gRPC 端口。Gateway 继续使用原有 Nacos 服务发现和负载均衡。

不再使用 `IAM_GRPC_ADDRESS`、`TENANT_ACCESS_GRPC_ADDRESS`、`ENTITLEMENT_GRPC_ADDRESS` 或 `IAM_HTTP_BASE_URL` 指定下游实例。地址必须对调用方真实可达；混合本机/容器联调时不能把容器内 IP 当成宿主机可达地址，须在部署层正确发布和登记端口，不通过调用方静态地址绕过 Nacos。

发现失败、没有健康实例或 gRPC metadata 非法时，调用明确失败，不访问旧地址。当前直接查询实现选择首个健康、启用且权重大于零的实例；不承诺跨实例均匀分配流量。gRPC 复用连接、地址变化时更换连接，发现与 RPC 共用最多 3 秒的截止时间；调用方更短的截止时间继续生效。

Nacos 只决定连接地址。命名 gRPC 通道继续使用 Spring 的 `spring.grpc.client.channels.<name>` TLS/SSL Bundle、默认 deadline 和拦截器配置；服务名用于 TLS 身份校验，证书须覆盖该服务名。缺失 SSL Bundle 不得回退明文。没有修改任何环境既有 TLS 策略或证书材料。

## 仅在需要时使用本地业务配置

此模式仅用于开发，需主动选择；默认不生成或保留实际文件。

1. 复制当前应用的 `deploy/nacos/dev/<application>.yaml` 为该模块 `src/main/resources/application-local-file.yaml`，作为自己维护的业务配置。不要复制其他应用的文件，也不要写入密码。
2. 在文件末尾追加下列 YAML 文档（保留 `---` 分隔符），将 `<component>` 替换为 `gateway`、`iam`、`tenant-access`、`entitlement` 或 `audit`：

   ```yaml
   ---
   spring:
     cloud:
       nacos:
         config:
           enabled: false
           import-check:
             enabled: false
         discovery:
           enabled: true
   saas.forge:
     <component>:
       configuration-revision: local
   ```

3. IDE Program arguments 改为 `--spring.profiles.active=local-file`。保留上述启动连接设置和凭据目录导入，不在 IDE 中强制添加 `nacos:` import。仍通过 Nacos 发现下游服务。
4. 恢复 Nacos 模式时清空 `local-file` profile，删除个人文件及 `target/classes` 中对应生成副本，再由 IDE Build/重启。个人文件受 Git 忽略并排除出发布 JAR，但 `target/classes` 不能当部署制品分发。

## 更新当前开发 Nacos

先核对 Compose `.env` 经 Compose 解析后的五组工作负载凭据与 IDE 凭据文件一致，保存旧 Nacos 配置供回滚。不要直接 `source .env`；其中可能有 Compose 专用的美元符号转义。

在仓库根执行：

```bash
bash scripts/validate-nacos-config.sh
docker compose -f deploy/compose/compose.yaml run --rm --no-deps nacos-init
```

该命令复用已运行的 Nacos，不启动或替换应用，也不执行数据库迁移。`nacos-init` 会把账号密码设为 `.env` 中的值、补充权限、发布五份 dev 清单并检查工作负载可读性；因此必须先确认凭据一致。同步后以各自工作负载身份读回五份配置并与清单逐项核对。

清单内容有变更时递增对应 `configuration-revision`；没有内容变化时无需人为增加版本。`refreshEnabled=false` 保持不变，配置和 IDE 启动设置需要开发者 Build 并重启对应应用后生效。确认实际日志中的 Nacos 配置加载与版本，再检查对应业务链路；上传成功不等于服务已切换。

此入口仅适用于当前本地开发 Nacos。test/staging/prod 继续使用既有受控发布流程。
