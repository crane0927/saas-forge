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
- 真实 Chrome/Fresh Compose 页面验收：预检阻断。Chrome 153.0.8010.36 可用，但当前本地 HTTPS Edge 占用 443；已请开发者释放，不接管 IDE 或现有 Edge。

## 正式验收入口

`consoles/integration-test/plan-acceptance.mjs` 接入既有 Chrome/Fresh 产品套件：生产页面创建、激活、筛选、服务端提交后丢弃响应、刷新恢复、历史零额度英文读取、浏览器重启与重新登录。历史记录仅在隔离 Fresh 数据库准备。

生命周期脚本原新建零额度夹具改为合法上限 1，再以正式授权 gRPC Consume 占满、HTTP 初始化验证 `QUOTA_EXCEEDED`，最后 Release 验证已用量为 0；不通过修改用量表伪造耗尽。

本切片不自动关闭 #170 或 #165。前后端完整门禁与生命周期 Fresh 均已通过；真实 Chrome 页面验收仍被 443 占用阻断，因此不宣称 #174 全部验收通过。当前开发库仅做过只读盘点，尚未代开发者应用 V6 或重启 IDE 服务。
