# Issue #178：OAuth Client 列表与详情读取

日期：2026-09-13。实现基点：`9f2d2634f17866ce8695e0c8e0769cf3078474af`。本记录区分代码验证与真实服务验收，不作为关闭 Issue 的完整依据。

## 实现范围

- 新增正式 `GET /api/v1/platform/oauth-clients`，复用详情 operation；IAM 每次验证当前平台管理员授权。
- `name` 为区分大小写的字面子串，`clientType`、`status` 为枚举筛选；固定 ID 升序，默认 50、最大 100。游标绑定资源和筛选，24 小时过期；失配、过期、非法输入返回 400。
- 列表与详情仅返回非敏感字段，使用 `Cache-Control: no-store`；没有 Secret 或摘要。查询不修改数据库，不新增迁移。
- Console 复用生成 Client、共享 Runtime、Design System 和双语资源。提供筛选、前后分页、详情导航及重新读取；403/不可用提供明确反馈，会话变更后的旧响应由 Runtime 拒绝，卸载/切换请求由 AbortController 隔离。
- 未增加凭据管理页面；新列表不扩展 Java SDK 的已批准发布 operation 集合。

## 已执行验证

- TDD：新增 HTTP 测试先得到 `200 expected / 405 actual`，补列表后通过。
- `./mvnw -pl services/iam-service -am verify -Dit.test=AuthenticationHttpIT -Dfailsafe.failIfNoSpecifiedTests=false`：通过。IAM 单元测试 164 项、HTTP 集成测试 56 项；依赖模块单元测试 116 项通过。集成测试使用隔离 PostgreSQL/Redis，包含分页、字面筛选、过期/失配游标、无效页大小、未登录和撤销平台角色拒绝、Secret 排除及已有 OAuth/认证路径。
- 新增 Runtime 迟到响应测试；`authentication-runtime.test.ts` 63 项通过，验证登出后旧列表不回填。
- Chrome 153 的页面测试通过，使用明确标识的 HTTP 夹具，验证列表筛选、分页、详情、焦点、403/503、英文重新读取和 axe 无障碍检查。这不是真实 IAM/Gateway 联调证据。
- 类型检查、ESLint、国际化校验及工作区生产构建通过。生产构建保留已有大 chunk 提示。

初次全套 HTTP 测试因新增测试排在已有 Redis 停止测试之后而失败，已调整测试顺序并完整复跑通过。初次前端全套有两项组件查询超时，单独复跑 6 项通过；最终前端全套复跑通过：104 项边界测试、321 项包内测试，无跳过。

补充门禁：`V1ContractCompatibilityTest`、`JavaSdkOpenApiPublicationTest`、`RepositoryStandardsTest` 与路由目录/浏览器 Session Slot 契约共 25 项通过。路由目录包含新增 IAM `USER_REQUIRED` operation，总数从 50 更新为 51。最终 Chrome 4 项通过，名称、类型、状态筛选全部覆盖；类型和状态使用键盘选择。

## 代码审查

- Standards：无阻断问题；已修正浏览器夹具为 IAM 合法的 `tenant-access:membership:read` scope。
- Spec：初次审查保留真实服务验收缺口，后续第九轮已补齐专项真实产品证据。新列表未扩大 Java SDK 发布边界，路由与验收脚本增量经复核。
- 本次最终 Standards / Spec 审查均无阻断项，确认测试上下文隔离、按钮对比度修复、验收脚本及失败记录没有放宽既有检查。

## 真实产品验收

`oauth-client-acceptance.mjs` 已接入既有 `console-authentication.test.mjs`，通过正式 Client 准备 51 个运行时服务 Client，验证真实页面筛选、分页、详情、刷新、重新登录、另一语言和响应敏感字段排除；403/503 明确标为浏览器故障注入，真实授权拒绝由 HTTP 集成测试验证。

首次本机执行 `bash scripts/verify-console-authentication-e2e.sh --preflight` 被阻塞：

- 未设置绝对路径 `SF_ACCEPTANCE_TLS_CERT` / `SF_ACCEPTANCE_TLS_KEY`。
- `127.0.0.1:443` 已有服务监听，不能覆盖开发者环境。

随后开发者释放 443，复用 `deploy/compose/.secrets/local-https-development/server.pem` 与 `server.key` 的绝对路径注入上述环境变量；DNS、证书材料、443 和 Chrome 153 TLS 导航预检通过。没有生成替代证书或改变系统信任。

真实开发页面的 GET 405 已定位到旧 Gateway 进程仍使用启动时加载的路由目录；同机 IAM 已支持 GET（未登录请求返回 401）。新增 OpenAPI operation 会改变 Gateway 的生成路由制品，因此这次需要开发者重启 Gateway 加载新目录，不表示每次 IAM 改动都需要重启 Gateway。

完整验收重跑期间发现并处理：

- 第一次被 Testcontainers Kafka 启动脚本 `Text file busy` 阻断；重试后未再出现。
- 新查询引入 `Clock` 后，持久化专用测试上下文缺少 Bean；补充固定时钟，并使用 `@TestConfiguration` 加 HTTP 测试显式扫描排除，避免固定时钟泄漏到认证上下文。联合复跑 HTTP 56 项、持久化 17 项通过。
- Chromium 捕获到普通按钮从禁用恢复的颜色过渡，以及稳定悬停颜色对比度 3.32:1。静态检查等待动画结束；共享默认按钮 hover/active 文字改用既有语义文字色，保留完整 axe 检查并新增明暗主题悬停回归。OAuth Client 4 项复跑通过。此结果不宣称每一帧过渡对比度均通过。

2026-09-13 第五轮完整 Maven `verify` 通过，覆盖全部 26 个 reactor 模块、前端工作区检查和生产构建；Design System 22 项及 Console 消费者 24 项 Chromium 浏览器测试通过，包含新增明暗主题普通按钮悬停对比度回归。运行基点为 `0413dc2` 加本次工作区修正。

产品阶段初次运行发现语言初始化断言及中英文旧占位页标题未同步；已改为英文浏览器上下文通过页面切换中文，并将导航、恢复和故障注入标题同步为 `OAuth Clients`。随后使用 `--product` 复用已验证构建，真实 IAM 完成 51 条数据准备、筛选、50/1 分页、详情与刷新；切换语言后应保留语言选择器焦点，已修正该焦点断言。失败轮次不计为产品验收通过。

后续产品轮次还修正了同名页面 H1/表格 H2 的定位歧义、首次登录语言切换焦点时序，以及 Chrome 导航后释放旧响应体导致证据无法读取的问题。响应到达时即检查正文，仅保留状态码、可读性和敏感字段检查布尔结果，刷新前等待采集完成；不保存正文或忽略读取失败。邮件故障恢复场景有两轮分别在恢复返回值和状态刷新处失败，后续完整通过，未据此修改无关业务实现。第八轮宿主 443 转发连接重置，仅重启该随机验收项目的 TLS 容器后 Chrome 四入口检查通过；未操作日常开发环境。

第九轮独立 Fresh Compose 的 `OAuth Client list filters, cursor paging and detail reload` 子项完整通过：正式 Client 经真实 IAM 准备 51 条数据，页面验证名称/类型/状态筛选、50/1 游标分页、详情导航、浏览器刷新、英文详情、登出再登录后的权威读取，以及响应敏感字段排除。403/503 为明确标记的浏览器故障注入；真实授权拒绝仍由 IAM HTTP 集成测试证明。

第九轮 Chrome 153 的完整真实产品门禁 **40/40 通过，失败、取消、跳过均为 0**，耗时约 161 秒。该轮正常启动，未重启任何容器；随机项目为 `saas-forge-console-1789283456-53353-95e219`。证据在 `/tmp/issue178-fresh-evidence-r9/acceptance-run.json`、`product-summary.txt` 和 `oauth-client-details-en.png`；完整 Maven 成功摘要在 `/tmp/issue178-fresh-evidence-r5/maven-summary.txt`。这些为本机临时证据，不含响应正文或凭据。

脚本后续 `console-browser-chrome` 门禁通过，最终 `acceptance-run.json` 状态为 `passed`，进程退出码 0。已只读确认该随机项目无剩余容器、数据卷或验收镜像。Maven/workspace 完整成功来自第五轮，最终产品与 Chrome 浏览器成功来自第九轮 `--product`，不表述为第九轮重新运行了 Maven。

未接管、停止或替换 IDE 服务与现有 HTTPS 环境。远端当前提交 CI 未执行，Issue 保持打开；本记录不冒充远端 CI 或已推送提交的证据。
