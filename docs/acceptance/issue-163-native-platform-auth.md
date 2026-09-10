# Issue #163：Gateway 与 IAM 原生启动验收

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
