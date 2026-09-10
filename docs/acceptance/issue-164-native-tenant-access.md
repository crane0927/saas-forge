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

2026-09-10，本机 Gateway、IAM、Tenant Access 已通过 IDEA 原生 Debug 运行，使用 `local` profile。开发者已停止冲突的 Tenant Access 容器；个人配置及 configtree 被 Git 忽略，凭据文件权限为 600。

开发者明确授权后，已从指定停止容器复制必要工作负载凭据，并仅为 dev 补齐 IAM ↔ Tenant Access 的 naming 读取权限。没有重跑初始化或重置开发数据。两个工作负载的实例读取均返回 HTTP 200/code 0：

| 服务 | 注册 IP | HTTP | grpc.port |
| --- | --- | --- | --- |
| IAM | 127.0.0.1 | 8081 | 9091 |
| Tenant Access | 127.0.0.1 | 8082 | 9092 |

通过真实 Chrome `https://console.saasforge.test/` 使用既有合法身份登录，进入“Tenant 工作台”；再次刷新页面后会话恢复成功。请求沿既有 Console/Gateway 入口执行，没有直接注入 Cookie、Origin 或 Bearer Token。普通登录/刷新覆盖 IAM 到 Tenant Access 的 Accessible Membership 查询；该查询不要求 Membership Validation 的服务 Token，不能单凭登录/刷新推断反向 JWKS 已调用。受保护双向路径仍待补验。

将 Tenant Access 个人配置的 HTTP/gRPC 端口临时改为 8182/9192，经开发者在 IDEA 重启后，Nacos 返回新端口及健康状态；IAM 与 Gateway 配置未改，真实 Tenant Console 刷新仍恢复到工作台。

在 IDEA 停止 Tenant Access 后，IAM 的发现查询返回成功但实例列表为空；刷新真实 Tenant Console 显示“暂时无法恢复会话”，恢复代码 `TENANT_ACCESS_UNAVAILABLE`，未进入工作台。发现服务异常及旧端点仍存活时不回退的情况由真实临时 HTTP/gRPC 服务自动化测试覆盖，未中断共享 Nacos 来制造现场故障。

个人配置已恢复原端口 8082/9092，经开发者再次 IDEA Debug 后，浏览器刷新成功恢复工作台。

完整 CI、Fresh Compose、多浏览器矩阵尚未执行。Issue #164 在剩余现场验收完成前保持未完成状态。
