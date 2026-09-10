# Issue #164：Tenant Access 原生启动验收

## 范围与实现

延续 #163，只处理已有 Tenant/Identity/Membership 的会话与校验链路。Tenant 初始化及 Entitlement 联调不计入本切片。

- Tenant Access 个人模板、local Config Data、IDE classpath 加载和 JAR 排除配置已补齐；Flyway 仍关闭。
- local 下 IAM/Tenant Access 通过同一内部发现模块进行 HTTP/gRPC 寻址；每次调用直接查询 Nacos 健康实例，gRPC 端口来自被调用实例的 `grpc.port`，没有固定地址兜底。
- IAM 的 Accessible Membership 与 Membership Validation 使用同一发现通道；Tenant Access 的 IAM HTTP/JWKS/Token 和 IAM gRPC 适配使用发现。
- 非 local 入口保留原有 Spring gRPC 配置及生命周期管理；授权、Scope、保留 IAM Client 校验、Cookie、CSRF 和 Browser Session Slot 规则未修改。
- 新环境初始化补充 IAM ↔ Tenant Access 的最小 naming 读取权限；已有环境仍需管理员独立补齐，不向常驻应用注入管理员凭据。

## 自动化记录

- 配置 TDD：Tenant Access 测试先因个人模板不存在失败；补齐后加载成功，未连接配置中心，保留发现与 Flyway 关闭。
- 发现测试先因缺少实现编译失败；HTTP 测试初次执行受沙箱的 Mockito attach 限制，未进入行为断言。获准运行后，真实临时 HTTP/gRPC 服务端口切换通过。
- 后续覆盖：同一客户端跟随端口变化；旧服务仍存活时，空注册表与发现异常拒绝；缺失/非法 gRPC 元数据拒绝；授权元数据透传及服务端拒绝状态保留。
- `bash scripts/validate-nacos-config.sh`、初始化及 ACL 脚本语法检查、`git diff --check` 通过。未修改 `deploy/nacos/<environment>/` 的运行配置资源。

- 发现模块目前 11 项测试通过（失败/错误/跳过均为 0）；其中慢发现测试先在 1 秒断言超时，修复为发现与 RPC 共用剩余预算后通过。
- Standards 初审发现 1 项超时缺口，已修复并复核，当前 0 项未解决；Spec 审查未发现代码缺陷，但真实验收证据尚缺。审查不能替代现场验证。

完整相关模块验证通过：

```bash
mvn -q -pl gateway,services/iam-service,services/tenant-access-service -am verify
```

命令退出码为 0，本次新生成报告共 449 项测试，失败/错误/跳过均为 0：Gateway 44、内部发现 11、Tenant Access 109、IAM 260、Auth SDK 21、Route Catalog 4。IAM 的 96 项集成测试通过。测试中的临时 PostgreSQL/Redis 故障日志不作为失败结论，以 JUnit 报告和 Maven 退出码为准。日志位于本次执行机器 `/tmp/issue164-verify.log`；未执行仓库全部 CI 或未受影响模块矩阵。

本次生成的 Gateway、Tenant Access、IAM JAR 已检查，不包含个人 local 配置或模板。

## 真实环境记录

尚未完成本次真实 IDE 与浏览器联调。开发者已停止 Tenant Access 容器并提供受限浏览器凭据文件；本任务已准备被 Git 忽略的 Tenant Access 个人配置/configtree，并为 IAM 个人配置补充 gRPC 注册元数据。

只读检查确认：IAM 当前工作负载读取 Tenant Access 实例返回 403；仓库 `.env` 与运行环境凭据不一致。自动审批最初拒绝从停止容器复制准确凭据；开发者随后明确授权，已将指定停止容器中的必要凭据写入 600 权限的 configtree，并补齐仅 dev 范围的双向 naming 读取权限。两个工作负载的实例读取复查均为 HTTP 200/code 0，目前实例列表为空。没有重跑初始化、启动后台应用或重置开发数据。浏览器当前停留在既有 Tenant 登录失败页面（`TENANT_ACCESS_UNAVAILABLE`），不能据此宣称新实现的行为。

IDE UI 自动化返回 `noWindowsAvailable`，已请开发者同步 Maven 并原生 Debug 三个主类。

待补齐：

1. Tenant Access IDE Run/Debug、断点及修改后重启。
2. IAM 与 Tenant Access 在 Nacos 中注册可达 IP、正确 HTTP 端口和 `grpc.port`，并有双向最小读取权限。
3. 已有合法 Membership 经真实 Tenant Console/Gateway 登录、刷新、Membership 校验，以及反向 JWKS 请求；记录 IAM 内部服务 Token 签发证据。
4. 被调用实例地址/端口变化，调用方配置不动仍成功；无健康实例与发现失败时明确拒绝。

完整 CI、Fresh Compose、多浏览器矩阵尚未执行。Issue #164 保持未完成状态，不能凭配置或自动化测试关闭。
