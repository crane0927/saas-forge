# 开发配置来源与 IDE 启动设置

## 默认从 Nacos 读取

五个后端在 IDE 激活 `local`，业务运行配置读取当前开发 Nacos 的 `dev` namespace、`SAAS_FORGE` group，各自只读自己的 `<application>.yaml`。`local` 同时保留本机 Nacos HTTP/gRPC 服务发现，不能为了启用配置中心而直接移除。

仓库不再提供 `application-local.yaml` 实际文件及 `.example` 模板。以下内容分开维护：

- 业务策略、认证期限、重试和就绪检查：`deploy/nacos/dev/` 清单，经 `nacos-init` 同步到当前开发 Nacos。
- 本实例监听和注册地址、数据库/Redis/Kafka 连接、Nacos 连接和 Browser Root Domain：IDE 的启动设置。
- 密码、私钥和服务身份：各应用独立的环境变量或受限 `configtree` 目录。目录权限 700、文件 600；不把整个 Compose `.env` 提供给应用。

IDE 的 Program arguments 保留 `--spring.profiles.active=local`。在 Environment variables 中设置 `SPRING_APPLICATION_JSON` 为下方对应 JSON（可压成一行），按真实环境修改地址和绝对路径。`${...}` 是 Spring 的占位符，应原样保留；其中引用的密码由受限文件提供，不能替换成明文粘进 IDE。该 JSON 只承载启动和连接参数，不保存业务策略或 `configuration-revision`。

`spring.config.import` 在 IDE 中只导入凭据目录；应用自身 `application.yaml` 导入 Nacos 配置，不能关闭 Nacos Config，也不能加 `optional:` 掩盖读取失败。凭据目录中每个文件名是属性名，例如 `NACOS_IAM_PASSWORD`。各服务所需凭据键见对应原生开发文档。

以下是连接配置示例，不是服务已运行的证明。端口变更时同步监听和注册；gRPC metadata 始终引用本实例的监听端口。内部地址不替代受信 HTTPS 浏览器入口。

### audit-service

```json
{
  "spring.config.import": "configtree:/absolute/path/to/audit-service-secrets/",
  "spring.cloud.nacos.discovery.enabled": true,
  "spring.cloud.nacos.discovery.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.discovery.namespace": "${NACOS_NAMESPACE:dev}",
  "spring.cloud.nacos.discovery.ip": "${AUDIT_REGISTER_IP:127.0.0.1}",
  "spring.cloud.nacos.discovery.port": "${AUDIT_HTTP_PORT:8084}",
  "spring.datasource.url": "${AUDIT_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/audit_db}",
  "spring.datasource.username": "${AUDIT_DATABASE_USERNAME:audit_app}",
  "spring.datasource.password": "${AUDIT_DATABASE_PASSWORD}",
  "spring.kafka.bootstrap-servers": "${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:29092}",
  "server.address": "${AUDIT_BIND_ADDRESS:127.0.0.1}",
  "server.port": "${AUDIT_HTTP_PORT:8084}",
  "spring.cloud.nacos.config.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.config.namespace": "${NACOS_NAMESPACE:dev}"
}
```

### entitlement-service

```json
{
  "spring.config.import": "configtree:/absolute/path/to/entitlement-service-secrets/",
  "spring.cloud.nacos.discovery.enabled": true,
  "spring.cloud.nacos.discovery.metadata.grpc.port": "${spring.grpc.server.port}",
  "spring.cloud.nacos.discovery.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.discovery.namespace": "${NACOS_NAMESPACE:dev}",
  "spring.cloud.nacos.discovery.ip": "${ENTITLEMENT_REGISTER_IP:127.0.0.1}",
  "spring.cloud.nacos.discovery.port": "${ENTITLEMENT_HTTP_PORT:8083}",
  "spring.datasource.url": "${ENTITLEMENT_DATASOURCE_URL:jdbc:postgresql://127.0.0.1:5432/entitlement_db}",
  "spring.datasource.username": "${ENTITLEMENT_DATASOURCE_USERNAME:entitlement_app}",
  "spring.datasource.password": "${ENTITLEMENT_APP_PASSWORD}",
  "spring.data.redis.host": "${REDIS_HOST:127.0.0.1}",
  "spring.data.redis.port": "${REDIS_PORT:6379}",
  "spring.data.redis.password": "${REDIS_PASSWORD}",
  "spring.grpc.server.port": "${ENTITLEMENT_GRPC_PORT:9093}",
  "server.address": "${ENTITLEMENT_BIND_ADDRESS:127.0.0.1}",
  "server.port": "${ENTITLEMENT_HTTP_PORT:8083}",
  "spring.cloud.nacos.config.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.config.namespace": "${NACOS_NAMESPACE:dev}"
}
```

### gateway

```json
{
  "server.address": "${GATEWAY_BIND_ADDRESS:0.0.0.0}",
  "server.port": "${GATEWAY_HTTP_PORT:8080}",
  "spring.config.import": "configtree:/absolute/path/to/gateway-secrets/",
  "spring.cloud.nacos.discovery.enabled": true,
  "spring.cloud.nacos.discovery.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.discovery.namespace": "${NACOS_NAMESPACE:dev}",
  "spring.cloud.nacos.discovery.ip": "${GATEWAY_REGISTER_IP:127.0.0.1}",
  "spring.cloud.nacos.discovery.port": "${GATEWAY_HTTP_PORT:8080}",
  "spring.data.redis.host": "${REDIS_HOST:127.0.0.1}",
  "spring.data.redis.port": "${REDIS_PORT:6379}",
  "spring.data.redis.password": "${REDIS_PASSWORD}",
  "saasforge.environment": "dev",
  "saasforge.local-replacement.enabled": false,
  "spring.cloud.nacos.config.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.config.namespace": "${NACOS_NAMESPACE:dev}"
}
```

### iam-service

```json
{
  "spring.config.import": "configtree:/absolute/path/to/iam-service-secrets/",
  "spring.cloud.nacos.discovery.metadata.grpc.port": "${spring.grpc.server.port}",
  "spring.cloud.nacos.discovery.enabled": true,
  "spring.cloud.nacos.discovery.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.discovery.namespace": "${NACOS_NAMESPACE:dev}",
  "spring.cloud.nacos.discovery.ip": "${IAM_REGISTER_IP:127.0.0.1}",
  "spring.cloud.nacos.discovery.port": "${IAM_HTTP_PORT:8081}",
  "spring.datasource.url": "${IAM_DATASOURCE_URL:jdbc:postgresql://127.0.0.1:5432/iam_db}",
  "spring.datasource.username": "${IAM_DATASOURCE_USERNAME:iam_app}",
  "spring.datasource.password": "${IAM_APP_PASSWORD}",
  "spring.data.redis.host": "${REDIS_HOST:127.0.0.1}",
  "spring.data.redis.port": "${REDIS_PORT:6379}",
  "spring.data.redis.password": "${REDIS_PASSWORD}",
  "spring.kafka.bootstrap-servers": "${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:29092}",
  "spring.grpc.server.port": "${IAM_GRPC_PORT:9091}",
  "server.address": "${IAM_BIND_ADDRESS:127.0.0.1}",
  "server.port": "${IAM_HTTP_PORT:8081}",
  "browser.rootDomain": "${BROWSER_ROOT_DOMAIN:saasforge.test}",
  "spring.cloud.nacos.config.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.config.namespace": "${NACOS_NAMESPACE:dev}"
}
```

### tenant-access-service

```json
{
  "spring.config.import": "configtree:/absolute/path/to/tenant-access-service-secrets/",
  "spring.cloud.nacos.discovery.enabled": true,
  "spring.cloud.nacos.discovery.metadata.grpc.port": "${spring.grpc.server.port}",
  "spring.cloud.nacos.discovery.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.discovery.namespace": "${NACOS_NAMESPACE:dev}",
  "spring.cloud.nacos.discovery.ip": "${TENANT_ACCESS_REGISTER_IP:127.0.0.1}",
  "spring.cloud.nacos.discovery.port": "${TENANT_ACCESS_HTTP_PORT:8082}",
  "spring.datasource.url": "${TENANT_ACCESS_DATASOURCE_URL:jdbc:postgresql://127.0.0.1:5432/tenant_access_db}",
  "spring.datasource.username": "${TENANT_ACCESS_DATASOURCE_USERNAME:tenant_access_app}",
  "spring.datasource.password": "${TENANT_ACCESS_APP_PASSWORD}",
  "spring.data.redis.host": "${REDIS_HOST:127.0.0.1}",
  "spring.data.redis.port": "${REDIS_PORT:6379}",
  "spring.data.redis.password": "${REDIS_PASSWORD}",
  "spring.kafka.bootstrap-servers": "${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:29092}",
  "spring.grpc.server.port": "${TENANT_ACCESS_GRPC_PORT:9092}",
  "server.address": "${TENANT_ACCESS_BIND_ADDRESS:127.0.0.1}",
  "server.port": "${TENANT_ACCESS_HTTP_PORT:8082}",
  "spring.cloud.nacos.config.server-addr": "${NACOS_SERVER_ADDR:127.0.0.1:8848}",
  "spring.cloud.nacos.config.namespace": "${NACOS_NAMESPACE:dev}"
}
```

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
   saasforge:
     <component>:
       configuration-revision: local
   ```

3. IDE Program arguments 改为 `--spring.profiles.active=local,local-file`。保留上述启动连接设置和凭据目录导入，不在 IDE 中强制添加 `nacos:` import。仍通过 Nacos 发现下游服务。
4. 恢复 Nacos 模式时改回仅 `local`，删除个人文件及 `target/classes` 中对应生成副本，再由 IDE Build/重启。个人文件受 Git 忽略并排除出发布 JAR，但 `target/classes` 不能当部署制品分发。

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
