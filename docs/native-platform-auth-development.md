# Gateway 与 IAM 原生本地开发

适用于 Issue #163 的 Platform 登录与刷新切片，遵循 ADR 0043、0044。应用由 IDE 直接 Run/Debug 主类；环境初始化、迁移、服务身份及 HTTPS Edge 独立准备。Tenant 会话所需的双向 gRPC 发现见 [Issue #164 联调说明](native-tenant-access-development.md)，本文件的 Platform 切片不代替该验收。

## 个人配置

首次在仓库根目录复制模板；已有个人配置时不要覆盖：

```bash
cp gateway/src/main/resources/application-local.yaml.example gateway/src/main/resources/application-local.yaml
cp services/iam-service/src/main/resources/application-local.yaml.example services/iam-service/src/main/resources/application-local.yaml
```

两个 `application-local.yaml` 已被根 `.gitignore` 忽略，`.example` 可以提交。IDE 编译时会把个人文件复制到 classpath，选择 `local` 即可加载，无需指定工作目录或额外配置路径。两个模块的 Maven JAR 打包配置排除个人文件及模板，Spring Boot 可执行 JAR 同样不携带它们；`target/classes` 是本地编译输出，仍可能含个人配置，不应作为部署制品直接分发。模板只包含非敏感配置和凭据引用；不要把真实密码、Client Secret 或 PEM 内容写进去。`local` 是仅供本地开发的显式 profile：它取消默认 Nacos 配置导入，由个人文件提供运行策略和 `configuration-revision: local`；Nacos 服务发现仍启用。未选择 `local` 的测试、部署入口保持原有 Nacos 配置加载行为。

在 IDE 环境变量中分别配置以下值，或使用权限受限的外部 Spring `configtree` 目录。该目录中每个文件名是对应属性名，例如 `NACOS_IAM_PASSWORD`；个人 YAML 可在现有 `spring` 下加入 `config.import: configtree:/absolute/path/to/iam-secrets/`。目录建议权限 700、文件 600，按应用隔离，只提供常驻应用需要的值，不导入整份 Compose 管理员环境。

| 应用 | 必需值或引用 |
| --- | --- |
| Gateway | `NACOS_GATEWAY_USERNAME`、`NACOS_GATEWAY_PASSWORD`、`REDIS_PASSWORD`、`IAM_JWT_ISSUER` |
| IAM | `NACOS_IAM_USERNAME`、`NACOS_IAM_PASSWORD`、`REDIS_PASSWORD`、`IAM_APP_PASSWORD`、`IAM_JWT_ISSUER`、`IAM_JWT_PEM_KEY_VERSION_REF`、`IAM_JWT_PEM_PRIVATE_KEY_LOCATION`、`SAASFORGE_SERVICE_CLIENT_ID_FILE`、`SAASFORGE_SERVICE_CLIENT_SECRET_FILE` |

私钥位置使用 `file:/absolute/path/to/key.pem`；Client ID 与 Secret 使用受限文件的绝对路径。签名版本引用必须与数据库已初始化的 ACTIVE Signing Key 一致，不能生成新私钥后直接替换既有数据库的签名元数据。两个应用的 issuer、环境和 Browser Root Domain 必须一致。

模板默认使用本机开发依赖：PostgreSQL 5432、Redis 6379、Kafka 29092、Nacos 8848。这些是基础设施地址示例，不是必需容器拓扑；可通过个人文件或模板列出的变量改为自己的环境。启用认证的 Kafka 需要另外注入其协议、机制及受限凭据属性。Nacos namespace、应用身份和网络可达性须与实际环境匹配，Nacos 客户端还需要能连接服务端 gRPC 端口。

## 独立准备

- 先由既有受控初始化任务完成 IAM Flyway 迁移、Signing Key、保留 Service Client 和 Platform Admin 的准备。复用现有数据及有效凭据时不重跑重置或替换流程。
- 常驻 IAM 默认使用 `iam_app`，只注入应用数据库密码；`spring.flyway.enabled=false` 保持不变。不提供 migrator、数据库管理员、Nacos 发布管理员或 Platform Admin 初始化凭据。
- 首次环境准备可参考 [Compose 初始化说明](../deploy/compose/README.md)，但不把完整 Compose 或后台 JAR 脚本设为 IDE 启动前任务。数据库、Redis、Kafka、Nacos 可独立存在于本机或可达的开发环境。
- 先检查同一服务的容器和本机实例。由开发者停止冲突实例并等其从注册表下线；本入口不会接管、替换或恢复其他进程。

## IDE Run / Debug

Maven 项目使用根 POM 支持的 JDK 17。首次导入并同步 Maven，使生成的契约源码和模块依赖进入 IDE 类路径；日常修改由 IDE 编译，不执行 `package`、JAR 启动或脚本生成参数。

| 配置 | Main class | 模块 classpath | Active profiles |
| --- | --- | --- | --- |
| Gateway | `io.saasforge.gateway.GatewayApplication` | `gateway` | `local` |
| IAM | `io.saasforge.iam.IamServiceApplication` | `iam-service` | `local` |

普通 Java Application 运行配置没有 Active profiles 栏时，只需填程序参数 `--spring.profiles.active=local`。IDE 启动前动作保留普通 Build，不添加 Maven package、Compose 或托管脚本。主 `application.yaml` 不设置默认 `local`，避免改变测试/部署启动行为。若曾按旧说明设置 `spring.config.additional-location` 指向模块 `config/`，请移除该参数。

先 Debug IAM，再 Debug Gateway。两个主类可同时运行，分别停止和重启；日志留在对应 IDE Console。Gateway 默认 HTTP 8080，IAM 默认 HTTP 8081、gRPC 9091。用 `GATEWAY_HTTP_PORT`、`IAM_HTTP_PORT`、`IAM_GRPC_PORT` 调整监听；`GATEWAY_REGISTER_IP`、`IAM_REGISTER_IP` 决定注册表发布的地址，必须从调用方可达。注册 HTTP 端口随对应 HTTP 监听端口变化。

应用默认绑定 `127.0.0.1`。若 HTTPS Edge 在 Docker 中，需要将 Gateway 的 `GATEWAY_BIND_ADDRESS` 设为 Docker 可达的本机接口（例如本地受控开发环境使用 `0.0.0.0`）；监听地址不成为浏览器入口。其他服务的容器需要访问本机 IAM 时，也必须先确认其网络可达性，不能把本机 `127.0.0.1` 当成容器内的目标地址。

## 真实浏览器认证

按 [Console 与独立 HTTPS Edge 说明](native-console-development.md) 准备证书、域名、现有 Console 和 Edge。浏览器仅访问 `https://platform.saasforge.test`。Edge 的现有 `deploy/compose/.secrets/local-service-replacement/api-target.json` 可设为：

```json
{"hostname":"host.docker.internal","port":8080}
```

这只配置 HTTPS 入口到 Gateway 的目标。Gateway → IAM 的路由与 JWKS 使用已有 Nacos 发现；IAM 使用内部 Client Credentials 应用服务获取自身服务令牌，保留 Client 凭据、Scope 与撤销检查，不再通过 HTTP 查找和调用自身，也不需要自身发现读取权限。

使用已有 Platform Admin 的常规密码在真实 Console 登录，刷新页面触发 Platform Session Slot 恢复。确认请求走 HTTPS API Origin、刷新携带 `sessionSlot: PLATFORM`，响应 Cookie 保持 host-only、Secure、HttpOnly、SameSite=Strict。不要在 Console 调用参数中手工注入 Origin、Cookie、Fetch Metadata 或 Bearer Token。

在 Gateway 路由处理入口及 IAM `AuthenticationController.login` / `refreshAccessToken` 放断点，以真实请求命中并恢复执行。修改后由 IDE 重新编译、重启，确认仍能登录/刷新。再改变 IAM HTTP 端口并重启，等待 Nacos 更新，保持 Gateway 下游配置不动，重复登录/刷新。

停止 IAM 并确认注册表无健康目标时，Gateway 应明确失败；不得切换到固定地址。发现异常的聚焦自动化检查与真实注册表验收分开记录。断点暂停期间请求可能超时，恢复后重新操作即可，不放宽认证或网络超时来掩盖失败。

## 验证范围

聚焦配置加载与发现检查：

```bash
mvn -pl gateway,services/iam-service -am \
  -Dtest=LocalConfigurationTest,ReservedIamServiceAccessTokenProviderTest,GatewayJwksRouteTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

配置测试经 Spring Config Data 加载实际模板；内部服务令牌测试覆盖身份、精确 Scope、缓存及失败关闭；Gateway 路由测试继续覆盖发现边界。它们不能代替 IDE 操作、真实 Nacos、数据库及浏览器联调。相关模块完整测试与集成检查可用 `mvn -pl gateway,services/iam-service -am verify`；仓库完整流水线由 CI 承担。

验收记录须分别标明自动检查、IDE 断点/重启、Platform 登录刷新、IAM 端口变化、无健康目标及未执行项，不能用配置加载或进程启动成功代替真实认证成功。
