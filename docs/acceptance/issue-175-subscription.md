# Issue #175：Tenant 首个 Subscription 与权益读取

对应 #175 与父规格 #170。Tenant 详情增加合规 ACTIVE Plan 选择、首个 Subscription 创建、有效期和权威 max_users 上限/已用量；不增加全平台订阅管理、换套餐或续订。

## 数据与恢复边界

- 2026-09-12 实施前对本地 `entitlement_db` 执行只读事务：Flyway V1–V6 成功，1 条订阅、0 条零额度 Plan。该事实仅代表当前本地库。
- 正式 `GET /api/v1/platform/tenants/{tenantId}/subscription` 在 Entitlement 自有数据库的同一只读快照中设置 RLS Tenant 作用域并读取 Subscription、Plan Quota 和 Usage。`observedAt` 与 `effective` 描述查询时有效性，持久 ACTIVE 不表示永不过期。历史零额度保留原值。
- 无订阅时 `subscription`、额度上限和用量均为 null；有订阅但尚无计量行时权威初始用量为 0。读取失败不会变为上述空结果。
- V7 只新增 `subscription_recovery` 并导入现存稳定订阅幂等响应及原截止时间；不修改历史迁移或已有权益。登记独立提交，业务、幂等、Outbox 和恢复结果在持锁的同一事务内提交。保留期后已提交结果可读，未知结果禁止重建。
- 恢复查询按原 actor 与 Tenant 隔离；继续操作同时检查当前平台权限与原 Key。Key 只保存在共享 Runtime 私有 WeakMap，页面不获取凭据或保存完整请求。新请求非法过去时间不登记，历史原 Key 仍先查稳定结果。
- Tenant 与权益区域分别加载和报错，Plan/未决记录未知时禁用创建。Plan 刷新开始即丢弃旧候选状态，不能在本轮 Plan 响应前按旧数据提交。

## 验证记录

- 已验证红灯：新增订阅读取与恢复列表 HTTP 返回 404；Runtime 原操作恢复方法尚不存在。首次 Docker 沙箱限制不记为红灯或通过。
- 已通过阶段验证：Entitlement Controller/创建规则与 PostgreSQL 集成回归；最新阶段 26 项 PostgreSQL 用例通过。含读写、重复订阅、RLS 作用域、原 actor 恢复、24 小时边界及 Outbox 失败后继续原操作。
- 已通过阶段验证：Runtime 恢复原 Key 用例、页面响应丢失后权威回读、局部 Entitlement 失败保留 Tenant 与英文过期展示；初次 jsdom 缺少 ResizeObserver 的失败已通过现有环境替身修正。
- 代码审查：Standards 建议恢复文案使用单一 kind 与集中映射，已修正；Spec 发现刷新中旧 Plan 提前启用创建，已修正并补延迟/失败回归。两轴复审未发现新增阻断问题。
- 用户授权重新生成四域证书；复用本机 mkcert CA，Chrome 153.0.8010.36 的 DNS/证书/443/TLS 预检已通过。证书和私钥仅放受限临时目录，不提交。
- 完整前端 `pnpm --dir consoles run verify:workspace` 已通过，退出码 0，包含类型、lint、格式、workspace 测试、Chromium 矩阵及生产构建。Runtime 当时 132 项、Platform Console 31 项；随后增加异常权益响应测试，Runtime 独立完整 verify 135 项通过。
- 完整后端首轮在最后的 Java SDK 发布白名单门禁失败：订阅读取误加 Java SDK 发布标记。移除本票不需要的公开面扩展后，`./mvnw -Pbackend-local verify` 完整复验通过，退出码 0，耗时约 5 分 39 秒；Java SDK 发布边界保持不变。Entitlement PostgreSQL 26 项覆盖真实计量后 0→1→0 权威回读。
- Chrome/Fresh `verify-console-authentication-e2e.sh --product` 已通过，退出码 0：产品 37/37、0 失败/跳过，随后重置环境的 Chrome 浏览器门禁通过。目标为受信 HTTPS 四域，Chrome 153.0.8010.36，1440×960 与 320×900。
- 浏览器本票验证真实 Plan 选择与首订阅创建、同一真实请求换 Key 后重复创建返回 `SUBSCRIPTION_ALREADY_EXISTS`、响应丢失后的权威回读与原操作者刷新/重启/重新登录恢复；隔离库另一 actor 的记录不出现在当前用户恢复列表。零额度 Plan 返回 `PLAN_INVALID`，页面读取后 Tenant 过期/关闭分别返回 `TENANT_EXPIRY_REACHED` / `TENANT_INVALID_STATE`。
- 页面明确读取权威 limit=1、used=0；真实计量变化由 PostgreSQL 公共用例验证 0→1→0。浏览器还验证历史持久 ACTIVE 订阅已经过期时显示 Expired、故障后无假空结果、双语、路由焦点、存储边界及窄窗口无横向溢出。
- 截图人工检查通过：`/tmp/issue175-chrome-evidence/issue-175-subscription-detail.png`、`issue-175-narrow-detail.png`、`issue-175-partial-failure.png` 与 `issue-175-expired-subscription.png`；页面有实际内容、没有框架错误覆盖或内容遮挡。本票浏览器脚本 pageerror 为 0。
- 所有结果是本地验收；未运行远端 CI。本地开发数据库仍为 V6，运行本版本服务前需由开发者通过既有 Flyway 入口应用 V7。

## 验收入口

`consoles/integration-test/subscription-acceptance.mjs` 接入既有 Chrome/Fresh 产品套件，覆盖 Plan 选择、真实创建响应丢失、重复请求拒绝、权威回读、刷新/浏览器重启/重新登录恢复、私有记录隔离、局部故障与双语。历史零额度与 Tenant 资格变化仅在隔离 Fresh 数据库准备；产品读取与拒绝仍通过正式浏览器请求验证。

新增正式读取与恢复 API 仅进入 Console Client，不扩大 Java SDK 发布面。既有创建契约保持每 Tenant 至多一条订阅；历史数据与原幂等响应继续读取。
