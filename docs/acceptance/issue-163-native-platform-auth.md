# Issue #163：Gateway 与 IAM 原生启动验收

> 当前实现已改为内部签发，下面关于 IAM 自身 HTTP 发现及其权限的记录仅描述此前实现，现已被文末调整说明替代。Gateway → IAM 仍使用 Nacos 发现。

## 范围

Platform Console → 受信 HTTPS Edge → IDE Gateway → Nacos 发现的 IDE IAM，覆盖 Platform 登录与刷新。Tenant 会话的双向 gRPC 路径属于 Issue #164。

## 实现与聚焦检查

- Gateway 与 IAM 分别提供个人配置模板和classpath 加载方式；实际文件被 Git 忽略，敏感值仅从环境或受限凭据目录注入。
- `local` profile 使用个人运行配置，取消默认 Nacos 配置中心导入，保留服务发现；非本地模式保留原行为。
- Gateway 既有路由/JWKS 发现保持不变；IAM 本地 HTTP Client 按 `iam-service` 查询健康实例，无静态地址兜底。
- 配置加载 TDD：两个模板缺失时分别失败，补齐模板和 profile 配置后分别通过。
- HTTP 发现 TDD：旧实现请求固定地址遭连接拒绝；改为发现后，同一 Client 在两台临时 HTTP 服务端口间切换成功。
- 聚焦检查通过：两个 `LocalConfigurationTest` 与 `LocalIamDiscoveryTest`（端口变更、空实例、发现异常、非本地行为），共 6 项。
- 第一次发现测试因沙箱禁止 Mockito JVM attach 未进入业务断言；取得执行权限后观察到上述预期红灯，未把环境失败算作行为测试证据。
- code-review 双轴检查及发现权限追加复核：Standards 0 项确认发现，Spec 0 项确认发现。真实运行证据不能由审查替代。
- 追加初始化权限检查通过：`sh -n deploy/compose/nacos-init.sh`、`bash scripts/validate-nacos-config.sh` 和 `scripts/test/local-service-replacement.test.mjs` 的 19 项回归。后者首次因沙箱不能监听端口而失败，取得权限后完整通过。

## 相关模块完整验证

`mvn -q -pl gateway,services/iam-service -am verify` 已通过（退出码 0）。本次新生成的 Surefire/Failsafe 报告合计 439 项：Gateway 44、IAM 262、Tenant Access 依赖模块 108、SDK Auth 21、HTTP Route Catalog 4；失败 0、错误 0、跳过 0。包含实际 Testcontainers 集成测试；这是所选模块及依赖的完整验证，不等于仓库 CI 完整流水线。

## 实际环境准备

2026-09-10 读取已有开发容器状态，确认 PostgreSQL、Redis、Kafka、Nacos、HTTPS Edge 独立存在。开发者确认停止 Gateway 与 IAM 容器；没有自动接管或恢复机制。个人凭据目录只复用现有应用级数据库、Redis、Nacos 身份和 IAM 签名/Client 文件引用，不提供 migrator 或管理员凭据；未执行迁移、重置数据或轮换身份。

只读检查发现 `.env` 中的 Nacos 密码与原应用容器不一致，导致初次登录接口返回 500；改为引用原容器实际应用凭据后登录成功。两个服务的健康实例均为空，符合开发者已停止应用容器的状态。IAM 身份查询自身实例返回 403，初始化代码已补声明自身只读发现权限，现有环境的精确授权待开发者确认；未重跑初始化。

HTTPS Edge 现有 API 目标文件指向 `host.docker.internal:8080`，仅改变入口到 Gateway 的目标。

## 待完成的验收


- 实际 IDE 双主类 Run/Debug、断点命中、修改后重启。
- 真实 Platform Console 登录与刷新。
- IAM HTTP 地址/端口变化后的真实 Nacos 发现和链路恢复。
- 无健康 IAM 目标时的真实失败响应。

IDE 自动化曾遇到 `noWindowsAvailable`、`cannotClickOffscreenElement` 和捕捉错误；已经请求开发者协助配置并 Debug 启动两个应用。不能将这些尝试计为 IDE 验收通过。

仓库 CI 完整流水线、Fresh Compose、多浏览器矩阵未执行。本记录未声明 Issue #163 完整验收通过。

复现说明：[Gateway 与 IAM 原生本地开发](../native-platform-auth-development.md)。本机临时日志位于 `/tmp/issue-163-*.log`，不随源码提交。

## resources 配置布局调整

按开发者确认的方式，将两个模板及被 Git 忽略的个人配置移至各模块 `src/main/resources/`。IDE 只需激活 `local`；主配置不指定默认 local。移除旧 `spring.config.additional-location` 参数后不再依赖模块工作目录。

本次运行 `mvn -q -pl gateway,services/iam-service -am -Dtest=LocalConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false package` 通过两个配置加载测试并完成打包。检查确认两个模块的 `target/classes/application-local.yaml` 均存在，而普通 `.jar.original` 和 Spring Boot `.jar` 中均不含个人配置及模板。JAR 排除发生在打包边界，不阻止 IDE 资源复制。`git check-ignore` 确认新位置的个人配置仍被忽略。

本次仅重跑与资源位置和打包直接相关的检查；前述 439 项完整模块验证属于布局调整前的记录。实际 IDE 和认证验收仍待完成。

追加审查发现模板测试可能同时加载 classpath 的个人配置，已改为仅从隔离临时目录加载主配置与模板，两个配置测试再次通过，避免依赖开发者凭据目录。

## 2026-09-10 追加实际验证

- 重新执行两个模块的 `LocalConfigurationTest` 与 `LocalIamDiscoveryTest`，Maven 退出码 0；日志 `/tmp/issue-163-final-focused.log`。
- 在 IntelliJ IDEA 点击 IAM Debug，观察到主类使用 `--spring.profiles.active=local` 和模块 `target/classes` 启动；随后 Nacos 返回 `iam-service` 健康实例 `127.0.0.1:8081`。尚未命中断点，不能据此判定完整 IDE 验收通过。
- Gateway 健康实例仍为空；其 IDE 控制台显示之前进程已退出。再次尝试启动时，IDE 自动化接口连续返回 `noWindowsAvailable`，未成功启动 Gateway。
- 使用系统证书校验请求：Platform HTTPS 页面返回 200，Gateway JWKS HTTPS 返回 502。没有把页面可达作为登录刷新成功的证据。
- IAM 应用身份查询自身实例仍返回 Nacos 403；现有环境的只读发现授权尚未执行。

因此本次仍未完成真实登录刷新、双服务断点、修改后重启、真实端口切换和无健康实例认证失败验收，Issue #163 不满足关闭条件。

## IAM 内部服务令牌签发调整

经开发者确认，IAM 获取自身保留 Client 的服务令牌改为调用 `ClientCredentialsTokenService.issue`，适用于所有 profile。保留受限凭据文件、规范 UUIDv7、固定 Membership Read Scope、Client/Secret 有效性、撤销状态和令牌缓存规则；没有直接绕过认证调用签名器。删除自调用 RestClient、静态 IAM HTTP 地址配置及仅为自身发现新增的 Nacos 读取权限声明。现有环境未执行权限扩展，Gateway 的 IAM 发现权限不变。

Provider 经 Spring 事务代理使用 `NOT_SUPPORTED`，挂起调用方事务，使签名前的 Signing Key 最大 TTL 更新按既有 Repository 事务独立提交。内部签发失败在 Provider 边界转换为 `TenantAccessUnavailableException`，避免误报为浏览器用户凭据错误或成员授权丢失。

事务回归先在无挂起时观察到预期失败（签名时仍存在外层事务），再加入事务挂起。认证集成测试现通过内部 Provider 获取服务令牌，随后跨真实受认证 gRPC 边界调用 Tenant Access；公开 OAuth HTTP 端点的原有测试仍保留。此前专门测试 IAM HTTP 自身发现的测试随无效实现删除。

最终运行 `mvn -q -pl services/iam-service -am -Dtest=ReservedIamServiceAccessTokenProviderTest,ClientCredentialsTokenServiceTest,JwtSigningServiceTest,GrpcMembershipValidationTest,AuthenticationHttpIT -Dsurefire.failIfNoSpecifiedTests=false test`，68 项通过（认证集成 52、Provider 3、Client Credentials 4、签名 5、gRPC 4），失败/错误/跳过均为 0。日志 `/tmp/iam-internal-verification.log`。首次回归新增用例的执行顺序落在既有 Redis 停机测试之后，且一项 Scope 测试数据违反 Client 创建约束；修正测试顺序及数据后重跑通过，未将首次失败计为通过。

`sh -n deploy/compose/nacos-init.sh`、`git diff --check` 与 19 项本地服务脚本测试通过。本次没有运行完整 CI 或重启用户的 IDE 应用，仍不宣称 Issue #163 的真实浏览器及 IDE 验收全部完成。
