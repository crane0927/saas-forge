# Issue #172：Tenant 创建、查询与服务端恢复

范围：[Issue #172](https://github.com/crane0927/saas-forge/issues/172)，父规格 [Console 身份与租户初始化闭环](../30-platform-console-tenant-initialization.md)。本记录区分代码验证与真实产品验收，不代表父 Issue #170 或整个第 2 阶段完成。

## 实现

- 正式 OpenAPI 新增 Tenant 名称/生命周期筛选、游标列表、权威详情，以及当前操作者的创建记录列表、单条读取和原操作恢复。服务端接口与 TypeScript Client 均从契约生成。
- 原创建接口先独立登记原 actor、Key 与请求，再将 Tenant、幂等结果、Outbox 与恢复结果放入同一事务。数据库事务互斥区分正在处理与已回滚的未提交状态；获取互斥后重新读取数据库，避免 MyBatis 会话缓存中的旧结果。
- 恢复只使用服务端登记的原请求，并重新执行当前平台权限检查及原操作者隔离。名称不是操作身份，同名资源不能证明先前请求是否提交。
- `COMMITTED` 表示已知已提交；`PROCESSING` 表示仍持有执行互斥；`NOT_COMMITTED` 表示读取时确认未提交且仍在重放期内；`UNKNOWN` 表示过期未决结果。找不到记录也必须核查，不能自动新建。未提交仅是读取时刻的观察，不授权更换 Key。
- 成功结果完成后保留 24 小时安全重放窗口。未完成登记自登记起 24 小时停止重放；边界使用严格早于。过期已知结果仍可指向资源详情，过期未决结果不再返回 Key。所有新查询返回 `Cache-Control: no-store`。
- 恢复请求的原始到期时间按 `Instant` 字符串保存，避免 PostgreSQL 时间精度改变请求指纹。V12、V13、V14 均为新增前向迁移，旧迁移未修改。
- 共享 Runtime 隐藏恢复 Key，页面只能使用本次权威读取获得的恢复对象；伪造对象和不同会话的旧对象被拒绝。浏览器不持久保存请求、邮箱、Token 或恢复 Key。
- Console 使用共享表格、表单、反馈和恢复面板，提供中英文列表、创建及详情。成功后重新读取详情；发出创建后阻止重复提交，超时或取消转入结果核查。未提交草稿不保存；路由导航、浏览器离站和主动退出登录均保护已修改表单。共享退出保护 Provider 仅接入 Platform；Tenant Console 与 Remote 的未使用组件文案检查继续保留。

## 已执行的聚焦验证

- `TenantCreationControllerTest`：正式新增读取/恢复均重新校验当前平台权限；既有创建和初始化接口回归。
- `TenantCreationPostgreSqlIT`：真实 PostgreSQL 名称筛选、同名游标分页、详情与游标筛选绑定；原操作者恢复和其他操作者拒绝；24 小时前一毫秒及精确到期；已知结果与过期未决结果；字段校验后沿用原 Key 修正；数据库拒绝写入导致的真实事务回滚及原操作恢复；纳秒精度请求指纹。
- Runtime：创建记录在新 Realm 中恢复、Key 不向页面暴露、伪造恢复对象拒绝；既有认证测试文件 49 项通过。
- 页面：服务端已提交后响应丢失，随后查找创建记录并进入权威详情，只发出一次创建；已修改表单退出登录时继续编辑/确认放弃两条分支通过。
- `pnpm --dir consoles run verify:workspace` 完整通过：类型、lint、格式、边界测试、全部 workspace 单元测试、Chromium 组件/消费者/会话检查、国际化校验和生产构建。Platform 9 项、Runtime 125 项单元测试通过。该门禁中的 Chromium 检查不等于本业务的真实服务产品验收。
- `./mvnw -Pbackend-local verify` 的后端服务、SDK 等模块通过，26 项 `TenantCreationPostgreSqlIT` 包括四请求并发创建唯一结果，以及真实事务持锁期间的 `PROCESSING`、拒绝重放、释放后 `NOT_COMMITTED`。该完整命令最初在质量模块因 Tenant 行覆盖率 89.61% 低于 90% 而失败。
- 随后新增正式 HTTP 映射连接真实应用服务和 PostgreSQL 的集成测试，验证创建、带筛选列表、详情、恢复读取和已提交结果重放，以及相关 `no-store` 响应；授权在此测试中使用固定操作者替身，当前权限拒绝另由 Controller 测试覆盖。新增测试聚焦执行通过，原 26 项与新增 1 项合计覆盖 27 项不同场景。
- 保留上述真实运行的 JaCoCo 数据，使用 `-Pbackend-local -pl quality-gates -am verify` 指定该模块全部 3 个单元测试类、4 个集成测试类聚焦复验，通过 20 项单元检查和 11 项集成检查。Tenant 服务行覆盖率 90.92%（1802/1982），分支覆盖率 71.49%（534/747）；未降低阈值。最终通过项由前端完整运行、后端完整运行的通过模块及失败项修复后的聚焦复验组成，不声称单次 `./mvnw verify` 全部成功。

红阶段与修复记录：缺少查询服务、缺少恢复服务/Runtime 能力、退出提示缺失、字段校验提前占 Key 和请求精度丢失均先复现失败，再修复。首次沙箱集成测试因 Docker 权限未执行，授权后重跑通过。完整验证发现 HTTP operation 数、Console 路由清单、lint 和消费者制品标记断言需更新，修正后前端完整门禁通过；Maven `-rf` 续验被完整 Reactor 约束拒绝，未绕过该规则。

## 真实浏览器与 Fresh Compose

新增 `consoles/integration-test/tenant-creation-acceptance.mjs` 已接入既有 Fresh Compose 产品测试。目标链路为 Chrome → 受信 HTTPS → Gateway → Nacos → 真实服务，覆盖创建、名称/状态筛选、重复点击、提交后丢弃响应、刷新、实际浏览器关闭重启、重新登录恢复、英文代表路径，以及隔离数据库拒绝写入后继续原创建。响应丢失使用服务端真实响应后 `route.abort` 注入；正常成功路径不伪造服务响应。

Browser plugin not available；沿用仓库 Playwright 与 Google Chrome 产品渠道。2026-09-11 执行 `bash scripts/verify-console-authentication-e2e.sh --preflight`，使用开发环境既有受限 TLS 文件，退出码 1：`127.0.0.1:443 已有监听服务，不能覆盖现有环境`；Chrome 153.0.8010.36 可用。未启动或停止任何开发服务。

当前尚未执行本轮 Fresh Compose，不能据脚本存在或组件测试通过宣称浏览器验收通过。需由开发者释放 443 后执行产品验收。Issue #172 保持未完成验收状态，不关闭。

## 审查与专项边界

Standards 和 Spec 两个独立审查发现并修复：恢复 POST 缺少正式 JSON 请求体、字段校验占用 Key、原请求时间精度损失、主动登出绕过表单确认。修复已复审。

最终补充的并发/持锁测试、Platform Provider 范围、实际消费者制品断言和 HTTP 集成测试均已只读复审，无未解决的阻塞意见。

仓库 `AGENTS.md` 指定的 `java script/FlywayMigrationGenerator.java validate` 无法运行：该文件在当前检出中不存在。本轮通过现有 Testcontainers/Flyway 实际执行新增迁移；不将缺失的独立命令记录为通过。建议维护者确认并更新规范中的有效入口。
