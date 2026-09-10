# Issue #165：Entitlement 原生启动验收

## 实现范围

- Entitlement 增加个人 local 模板、文件 Config Data 与 JAR 排除配置；保留 Flyway 关闭，敏感值仍通过环境变量或受限文件提供。
- local 下 Entitlement → IAM HTTP/JWKS/服务 Token、IAM gRPC Platform Role、Tenant Access gRPC 资格查询，以及 Tenant Access → Entitlement Quota 均复用内部服务发现模块。
- 非 local 保留原有 Spring gRPC 通道配置及生命周期，不改正式业务 API、数据库结构或测试/生产安全策略。
- 新 dev 初始化补齐最小 naming 读取权限及 ACL 校验，未自动修改已运行环境权限或接管应用。
- 使用步骤见 [开发说明](../native-entitlement-development.md)。

## 自动化证据

配置测试先因缺少个人模板失败，补齐后通过。HTTP 通信测试通过真实临时服务器验证同一客户端跟随目标端口变化及空健康列表拒绝；Tenant Eligibility 与 Quota 测试先因缺少对应发现通道 Bean 失败，再补齐实现。测试中的 Nacos 为外部边界替身，HTTP/gRPC 为真实本机通信，不代表真实 Nacos 业务联调。

初次通信测试受沙箱 Mockito attach 限制，随后获准运行；Nacos 测试装配问题单独修正。开发途中未跟踪文件被清理，用户确认后已恢复本轮文件。

- 聚焦配置、真实临时 HTTP/gRPC 通信及相关装配测试通过，Maven 退出码 0。
- `bash scripts/validate-nacos-config.sh`、初始化/ACL 脚本语法和 `git diff --check` 通过；未修改环境 Nacos YAML 资源。
- Standards 审查：0 项发现。Spec 审查：1 项未完成的现场验收，见下文。
- `mvn -q -pl gateway,services/iam-service,services/tenant-access-service,services/entitlement-service -am verify` 退出码 0。本次报告共 516 项，失败/错误/跳过均为 0：Gateway 44、IAM 260、Tenant Access 110、Entitlement 66、内部发现 11、Auth SDK 21、Route Catalog 4。只统计本轮日志创建后生成的报告。
- Entitlement 发布 JAR 已检查，不含个人 local 配置或模板。
- 本机日志：`/tmp/issue165-focused.log`、`/tmp/issue165-verify.log`。临时数据库/Redis 故障测试日志不等同于测试失败，以 Maven 退出码及 JUnit 报告为准。

## 现场验收进度（2026-09-10）

开发者在 IDEA 启动 Entitlement 后，`http://127.0.0.1:8083/actuator/health/readiness` 返回 `{"status":"UP"}`。使用各工作负载身份读取真实 dev Nacos，得到以下健康注册：

| 查询身份 | 目标 | 注册 IP | HTTP | grpc.port |
| --- | --- | --- | --- | --- |
| Entitlement | IAM | 127.0.0.1 | 8081 | 9091 |
| Entitlement | Tenant Access | 127.0.0.1 | 8082 | 9092 |
| Tenant Access | Entitlement | 127.0.0.1 | 8083 | 9093 |

此前本地 `.env` 提供的 Entitlement 凭据登录失败，且 Tenant Access 读取 Entitlement 返回 403。经开发者明确授权，复用已停止开发容器的工作负载凭据到 Git 忽略的受限 configtree，并仅补齐 Entitlement → IAM、Entitlement → Tenant Access、Tenant Access → Entitlement 三项 dev naming 读取权限；随后登录及发现查询均成功。未重置数据、重建身份或启停容器。

以上仅证明启动、健康与注册发现，不代表业务联调通过。仍待完成 IDEA 重启、真实 Tenant 初始化及 Quota 副作用、真实 Nacos 端口变更和无健康实例演练。

仓库完整 CI、Fresh Compose 和多浏览器矩阵未执行。Issue #165 尚不能据此声明全部验收通过或关闭。
