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
- Spec：未发现代码缺陷或越界功能；保留真实服务验收缺口。新列表未扩大 Java SDK 发布边界，路由与验收脚本增量经复核。

## 真实产品验收待完成

`oauth-client-acceptance.mjs` 已接入既有 `console-authentication.test.mjs`，通过正式 Client 准备 51 个运行时服务 Client，验证真实页面筛选、分页、详情、刷新、重新登录、另一语言和响应敏感字段排除；403/503 明确标为浏览器故障注入，真实授权拒绝由 HTTP 集成测试验证。

本机执行 `bash scripts/verify-console-authentication-e2e.sh --preflight` 被阻塞：

- 未设置绝对路径 `SF_ACCEPTANCE_TLS_CERT` / `SF_ACCEPTANCE_TLS_KEY`。
- `127.0.0.1:443` 已有服务监听，不能覆盖开发者环境。

未接管、停止或替换 IDE 服务与现有 HTTPS 环境。当前会话的浏览器控制工具还报告 `Unable to load browser request-header policy`，未获得可验证的现有 Chrome 登录页面。真实 HTTPS → Gateway → Nacos → IAM、Fresh Compose 及当前提交远端 CI 均未完成，Issue 应保持打开。
