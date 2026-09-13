# Issue #174：Plan 管理与最小额度规则

## 范围

对应 #174，父规格 #170。新增 Plan 编码/状态筛选、游标列表、详情、创建与激活页面及正式读取/恢复接口。复用唯一 ACTIVE `max_users`；仍是单额度 Plan，不增加套餐编辑或订阅页面。

## 历史兼容

2026-09-12 实施前通过 `BEGIN READ ONLY` 核实当前本地 `entitlement_db`：Flyway V1–V5 成功；1 个 ACTIVE、上限 1 的 Plan；没有零额度订阅。已有五类 Bootstrap 稳定操作各 1 条。该结果仅代表当前本地库，不推断其他环境。

- 新请求 `NewPlanQuotaLimit.minimum=1`；历史响应 `PlanQuotaLimit.minimum=0`。领域新建、激活与新 Subscription 授予均检查正额度，既有读取/权益判定不改变。
- MVC 仅将创建请求 `quotaLimits[0].limit` 的 Min 校验延后到领域幂等判定之后，保留其他生成校验。历史原 actor/Key/字段匹配的稳定零响应可重放；新零请求仍拒绝。此例外不关闭整个 Validator。
- V6 只新增 `plan_recovery`，并导入升级前的 Plan 稳定响应、原字段及原到期时间。旧迁移与 `plan_quotas >=0` 约束不变，不升级或删除历史额度。
- 恢复只读返回原稳定结果，超过 24 小时依旧可读，但不能重放；未确认记录不产生新 Key。新请求合法范围从 0 收紧到 1 是明确的兼容变化；兼容门禁只允许创建请求该字段精确 0→1，其余字段、响应及其他值继续检查。
- 恢复登记独立提交，原操作互斥覆盖领域状态、幂等结果、Outbox 与稳定响应的事务。页面通过共享 Runtime 持有私有恢复材料，不保存完整请求或 Key。
- 创建恢复按原 Plan 编码限制对应操作，激活恢复按 Plan ID 限制；不同套餐仍可开始独立操作。永久非法字段不登记为可恢复操作。

## 验证记录

随实际执行更新；待执行不代表通过。

- 红灯：PostgreSQL 新建零额度用例实际未抛异常；Runtime 两项 Plan 恢复用例因方法尚不存在失败。
- 已通过：完整前端 `pnpm --dir consoles run verify:workspace`（类型、lint、格式、边界、全部 workspace 测试、Chromium 消费者/会话与生产构建），Runtime 130 项、Platform Console 28 项（Plan 7 项）。随后补充 activation 空 JSON 请求回归，Runtime 独立完整 verify 共 131 项通过。
- 已通过：精确兼容门禁及其例外范围回归；Entitlement Controller 7 项和 PostgreSQL 24 项。PostgreSQL 包含 V5→V6、MockMvc 历史零额度原请求重放、新请求拒绝、旧订阅权益、游标作用域、原 actor/Key、事务锁及精确到期边界。
- 首轮完整门禁失败：路由数由 37 增至 42 的断言未更新；前端 lint；后续服务测试调用签名错误。已修复；前端完整复验通过。全后端随后执行至最后的 SDK 发布边界，发现新请求模型未登记；仅追加 `NewPlanQuotaLimit` 白名单及源码迁移说明，实际 JAR 的 `JavaSdkReleaseBoundaryIT` 复验通过，最终 `./mvnw -Pbackend-local verify` 完整复验通过，退出码 0。
- 沙箱阻断：首次 PostgreSQL 无法访问 Docker；前端边界测试回环监听 `EPERM`。这些运行不计为通过。
- 两轴审查发现历史零额度 HTTP 提前校验、不可修复字段登记，以及所有套餐被单个未决操作阻断；已针对性修改并补回归，两轴复审各 0 项剩余阻断发现。Standards 另有重复消息选择的可选维护建议，本切片未扩展重构。
- 已通过：`bash scripts/verify-tenant-lifecycle-e2e.sh` 隔离 Fresh Compose 13/13，退出码 0；合法 limit=1 经真实 gRPC Consume 后用量为 1，HTTP 初始化返回 `QUOTA_EXCEEDED`，Release 后用量为 0。包含 Tenant、订阅、撤销、Audit 隔离重放和 Readiness 恢复。
- 历史阻断：本地 HTTPS Edge 占用 443，开发者停止后预检通过；未接管 IDE 或现有 Edge。
- 真实 Chrome/Fresh 页面验收最终通过：Chrome 153.0.8010.36、1440×960、受信 HTTPS 四域入口，`verify-console-authentication-e2e.sh --product` 产品 36/36，重置后 Chrome 浏览器门禁通过，退出码 0。该命令复用已构建产物，不宣称再次执行 Maven/workspace 门禁。
- 浏览器复验保留失败事实：首轮创建恢复等待超时；补充截图和请求计数后确认编码为空、请求数 0、页面异常数 0。列表与创建表单都有编码框，脚本未等待路由切换；改为等待 `/plans/new` 并限定创建表单后完整通过。期间另一轮四域出现 `ERR_CONNECTION_CLOSED`，在重新建立隔离环境后未复现。
- 本地原生环境：开发者报告操作记录读取失败；只读核实数据库仍为 V5、缺少 `plan_recovery`。执行既有独立 Flyway 入口后 V6 成功，应用角色可读 CREATE/ACTIVATE 各 1 条记录，原 `local-development` 仍为 ACTIVE、limit=1；开发者刷新后确认已正常。

## 正式验收入口

`consoles/integration-test/plan-acceptance.mjs` 接入既有 Chrome/Fresh 产品套件：生产页面创建、激活、筛选、服务端提交后丢弃响应、刷新恢复、历史零额度英文读取、浏览器重启与重新登录。历史记录仅在隔离 Fresh 数据库准备。

生命周期脚本原新建零额度夹具改为合法上限 1，再以正式授权 gRPC Consume 占满、HTTP 初始化验证 `QUOTA_EXCEEDED`，最后 Release 验证已用量为 0；不通过修改用量表伪造耗尽。

本切片不自动关闭 #170 或 #165。前后端完整门禁、生命周期 Fresh 13/13、真实 Chrome 产品 36/36 和重置后浏览器门禁均已通过。以上是本地验收结果，远程 CI 结果另记如下；尚未关闭 Issue。

本轮截图位于验收输出目录 `/tmp/issue174-chrome-evidence-4/`：`issue-174-create-response-lost.png`、`issue-174-activation-response-lost.png`、`issue-174-active-detail.png`、`issue-174-legacy-english.png`。页面身份、有效内容、无框架错误遮罩、交互状态、无障碍和存储检查通过；截图已人工检查，没有内容遮挡。证据对应实现提交 `bd98d6d` 加本次测试定位修正。


## CI 失败定位与复验

提交 `0e905b4` 的 Verify `34691695486` 在前端门禁失败：Design System 的表格更多操作/危险确认 jsdom 测试超过 15 秒，Chrome/Fresh 产品步骤因此未执行。Nacos 和 Tenant 生命周期任务通过；产物上传失败是前序门禁失败后没有生成浏览器证据的次生结果。

本地原用例未复现超时，但逐步计时确认约 8.3 秒耗在行和按钮的角色/可访问名称查询，渲染约 98 ms，菜单和对话框操作约 70 ms。没有将此记录为本地复现 15 秒超时。将该完整交互用例迁入现有真实浏览器门禁，保留行内前两个操作、更多菜单、危险确认对象断言，并增加复制操作的收纳与可见性检查；取消该用例的 15 秒特例，不增加全局超时、不删除检查。迁移验证中的真实 Chrome 定向用例通过，测试耗时 671 ms。初次放入既有展示文件时影响了后续焦点样式用例，改为独立 `server-table.browser.test.tsx` 后浏览器矩阵 20/20 通过；原展示文件不变。最终 `pnpm --dir consoles run verify:workspace` 完整通过，退出码 0，包含类型、lint、格式、全部单测、Chromium 矩阵/消费者/会话和生产构建。最终独立文件的真实 Chrome 复验 1/1 通过，测试耗时 391 ms。临时计时代码已清理。

本次修复尚未推送，不能将本地通过记为修复提交的 CI 通过。
