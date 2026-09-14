# Starter 默认身份检查验收（Issue #182）

范围依据 [Issue #182](https://github.com/crane0927/saas-forge/issues/182)。业务服务引入 Starter 并提供服务登记、IAM issuer、Nacos Discovery 和 Redis 配置，即使用统一认证；测试接收端原有的手写签名、JWKS 与撤销适配器已删除。平台与业务由同一批可信厂商开发者维护，Tenant 不自行开发或部署业务代码。

## 实现与证据映射

| 条目 | 实现与验证接缝 |
| --- | --- |
| Resource Server、RS256、Route Catalog | Starter 使用 Spring Security BearerTokenAuthenticationFilter，继续复用 sdk-auth 的算法、签名、issuer/audience、时间、Claim 和 User/Service 类型检查；原 HTTP 路由及 Scope 错误矩阵保留 |
| JWKS 缓存、合并、限频、限时 | IamJwksHttpTest 使用真实 HTTP JWKS、真实 RSA 签名、可控时钟和并发同步；验证五分钟到期、默认十秒刷新间隔、默认两秒等待、随机 kid、并发合并、已有公钥请求不阻塞、失败不续期及恢复 |
| Redis fail-closed | RedisAuthenticationHttpIT 使用 Redis 8.8.1；验证 jti/kid、Membership/Tenant Fence、client_id、Ready 缺失、MGET 被 ACL 拒绝及恢复；拒绝时不能进入业务处理 |
| 只读上下文 | TenantContextHttpTest 与既有 HTTP/公开 API 测试验证平台、租户、服务上下文互斥、不可写、异常清理、连续请求和子线程无串用；保留头、Tenant query/JSON 输入被拒绝，正常 JSON 可重放 |
| 认证前输入有界 | USER JSON 默认最多 1 MiB，`saas.forge.authentication.max-json-bytes` 可受控调整；超过上限返回 413，包括没有凭证的请求 |
| 缺配置与依赖恢复 | 自动配置测试验证缺少必需配置启动失败；HealthEndpoint readiness 必含认证检查，依赖故障为 DOWN，liveness 不加入认证依赖；可控 HTTP 测试验证 IAM/Redis 恢复 |
| 真实机制链路 | `verify-platform-mechanism-e2e.sh` 创建独立 Compose 项目、卷和临时密钥；真实 IAM、Redis、Nacos、Gateway、两个仅用默认 Starter 的接收端；覆盖 Gateway 与直连、用户/服务、Scope、Ready、客户端吊销、发现与故障切换 |
| 真实 IAM 换钥 | 同一专项中发布两个公钥，再由真实 IAM 使用新私钥签发；新旧凭证均验证成功，旧 kid 撤销后旧凭证失败、新凭证成功；浏览器会话通过正式 refresh 换取新用户凭证 |

JWKS 只通过 Nacos 发现的 `iam-service` 获取，不接受 Token 提供的地址。缓存更新失败不延长有效期；命中缓存仍读取撤销状态。公钥响应在网络接收时限制为 64 KiB，快照最多 64 个有效 RS256 公钥，不保存随未知 kid 输入增长的负缓存。

换钥专项只操作隔离数据库中的生命周期状态与临时开发私钥，将 `published_at` 设为五分钟前以模拟完成发布窗口，并按既有公式设置 RETIRING 保留时间。它证明真实签发/JWKS/接收端之间的轮换行为；不声称实际等待了五分钟或整个旧密钥保留期，不验收生产 KMS 或新增密钥管理 API。生命周期约束由既有 IAM 测试补充。

## 执行记录（2026-09-14）

环境：macOS aarch64、Oracle JDK 17.0.12、Maven 3.9.14；隔离 Compose 使用仓库固定的 PostgreSQL 18、Redis 8.8.1、Nacos 3.1.1 等镜像。未接管开发者的 IDE 或本地应用进程。

| 命令 / 检查 | 结果 |
| --- | --- |
| `bash scripts/verify-platform-mechanism-e2e.sh` | PASS，全部九阶段和新增 6b 换钥阶段完成，脚本退出 0，隔离资源清理完成；本机日志 `/tmp/starter-mechanism-complete.log` |
| `./mvnw --batch-mode --no-transfer-progress -Pbackend-local,sdk-external-consumer-acceptance verify` | PASS，27 个 Reactor 模块，709 项测试，0 failures/errors/skipped，耗时 6:58；日志 `/tmp/starter-backend-full.log` |
| Starter HTTP / 启动 / Redis 子集（包含在上项） | PASS，Tenant Context 6、JWKS 8、自动配置 5、路由过滤器 6、真实 Redis 4 项测试 |
| 外部消费者与公开发布面（包含在全量命令中） | PASS，外部消费者 6 项，JavaSdkReleaseBoundaryIT 4 项，CoverageThresholdIT 通过 |
| `./mvnw --batch-mode --no-transfer-progress -Pbackend-local,platform-mechanism-acceptance -pl test-support/platform-mechanism-receiver -am -Dtest=PlatformMechanismReceiverControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；默认 Reactor 外的专用接收端控制器测试，日志 `/tmp/starter-receiver-test.log` |
| `bash -n scripts/verify-platform-mechanism-e2e.sh`、`git diff --check` | PASS |
| 前端完整工作区、浏览器、远端 CI | 未执行；本次为后端认证范围，`backend-local` 只跳过前端聚合门禁，不跳过后端单元、集成和契约检查 |

上述记录对应本次实现工作树，不冒称推送后 SHA 的 CI 结果。早期 TDD 曾分别复现缺少默认适配、重复 JWKS 获取、未知 kid 不刷新、readiness 未注册、外部 Tenant 输入未拒绝；修复后相关测试通过。扩展真实专项时还发现重复登录活动 Browser Session Slot 返回 409，已改为正式 refresh，再完整重跑通过；早期失败不能作为通过证据。

## 审查与边界

Standards / Spec 两项独立审查原先指出认证前无界读取 JSON，以及缺少真实启动故障/换钥证据。已增加有界读取、HTTP 测试和真实专项场景；最终复核未发现新的阻断性偏差。Redis Ready=0 的独立判断以真实 Redis HTTP 测试为据；紧随 IAM 恢复的 readiness 503 也可能受公钥刷新冷却影响，不能单独归因 Redis。

本项不包含 Project/Task 页面、创建业务、事务级 Tenant 设置、RLS 或第三阶段完整产品浏览器闭环。未修改数据库迁移、Nacos 环境资源或 Redis Key Registry。远端 CI、发布到 Maven Central 和 Issue 关闭不属于本次本地实现结果。
