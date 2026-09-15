# Issue #177：密码设置通知状态与重新发送

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

Tenant 详情独立读取密码设置通知，区分待投递、邮件服务已接受、已有可用密码、不适用及需处理。邮件服务接受不代表收件箱送达；通知操作不重建初始管理员、不重新初始化 Tenant，也不消费或释放 Quota。

## 实现边界

- IAM 新增只读 gRPC，读取指定投递请求与当前 Credential。查询不签发 Challenge、不发送邮件；沿用保留 Tenant Access Client 的授权和 Nacos 发现，不读取其他服务数据库。
- Tenant Access 提供通知读取和原重发恢复 OpenAPI，继续复用正式重发 operation。公共最新投递与原操作者私有未决记录分开读取，避免另一操作者的新记录遮蔽原请求；有任何未决重发时不开放新的重发动作。
- 读取可按原 Key 或原重发 ID 精确关联，二者互斥；未指定时查当前操作者最早的未决重发。`operationState` 与邮件状态独立，缺失或过期记录返回 `UNKNOWN`，不从其他成功记录推断本次提交已完成。
- 恢复重新检查当前平台权限、原 actor、原记录和有效期，直接领取既有工作流 ID，不经过可能删除过期记录并新建的 prepare 入口。自动处理时仅观察，耗尽后原发起人可显式继续。24 小时已完成记录的恢复到期不被解释为新请求；未决工作流沿用原有持久恢复语义。
- 共享 Runtime 在当前会话内存中保存本次 Key／原恢复 ID，页面不持久保存请求、邮箱、Key 或凭据。只有精确关联的原操作 `COMPLETED` 可以解除未知提交；复制的恢复句柄及其他会话句柄被拒绝。
- 双语通知面板独立刷新，局部失败保留已确认信息并禁用依赖未知状态的动作。重发响应不用于推算 Tenant、Membership、Quota 或收件箱状态。
- 未修改迁移、Nacos 配置或依赖版本。

## 验证记录

- 红灯：新增通知 HTTP 测试返回 404；已激活 Tenant 的通知返回不适用；共享 Client 尚无查询方法；页面没有重发按钮。首轮 Docker 沙箱权限错误仅算环境失败，获审批后才记录业务红灯。
- 定向通过：Tenant Access PostgreSQL 31 项、IAM Password Setup PostgreSQL 5 项、通知 gRPC 4 项，无失败或跳过；后续并发关联和 HTTP 授权补充由最终完整门禁复验。
- 定向通过：共享 Runtime 单文件 62 项；通知页面 3 项，包含双击、旧成功记录不能解除本次 UNKNOWN、同一恢复 ID 响应丢失后的权威终态确认及英文代表路径。工作区类型检查通过。
- 两轴审查发现并修复“最新记录遮蔽原操作者未决请求”和“错误关联解除／不能解除 UNKNOWN”问题；规范、需求两轴复审均未发现新的明确问题。
- 完整后端 `./mvnw -Pbackend-local verify` 共执行 665 项测试，664 项通过、1 项失败、无错误或跳过。唯一失败为新 Console operation 被误标为 Java SDK 发布；移除该非本票范围标记后，定向复验 `JavaSdkOpenApiPublicationTest`、契约生成／兼容性与质量门禁通过（退出码 0）。此前测试编译阶段还修正过一次异常类型包名引用，未将失败计为通过。最终服务集成包含精确 Key／ID 关联、不同管理员并发、HTTP 403／404、过期拒绝及 Credential 变化后的通知结果。
- 通过：最终 `pnpm --dir consoles run verify:workspace`，退出码 0；含类型、lint、格式、全部工作区测试（Runtime 138 项、Platform Console 38 项）、Chromium 浏览器检查、国际化和生产构建。最新 Chrome 验收脚本的定向 lint 通过。测试中两处类型断言已改为 DOM 属性断言，并经类型和 lint 双重复验。
- 真实 Chrome/Fresh 首轮执行 39 项，37 项通过、2 项失败（#177 子场景及其父场景）。Gateway 日志确认原无请求体重发 POST 缺少 JSON Content-Type，被浏览器安全过滤器拒绝；共享 Runtime 在正式 operation 的初始化回调中合并请求头，保留授权及原幂等键。请求头回归先红后绿，最终 Runtime 138 项、类型、lint、格式全部通过。使用中间构建的第二轮主动终止并清理，不计通过；第三轮复用了旧 Console dist，同样失败。`--product` 只检查和复用制品，不替代构建；重新执行 `pnpm --dir consoles run build:workspace` 通过。
- 第四轮真实重发 503、其他操作者恢复 404、原操作者恢复 204、Mailpit 收到邮件以及 Key／投递 ID／Membership／用量不变断言全部通过，随后因验收脚本漏传页面标题而失败，整轮不计通过。已补齐无障碍检查标题，并将语言切换参数改为 helper 要求的显示名称；脚本 lint 通过。
- 第五轮 Fresh 的 `product-chrome` 39 项全部通过，无失败、取消或跳过。覆盖真实 SMTP 故障、原工作流恢复、独立授权、权威刷新、业务关系与用量不变、双语、路由焦点和安全存储；400px 桌面 Chrome 窗口无横向溢出。已人工核对需处理、其他操作者拒绝、邮件服务接受及英文截图。同轮 `console-browser-chrome` 通过，脚本退出码 0，`acceptance-run.json` 总状态与 Chrome 渠道均为 passed；已只读核对本轮临时容器、数据卷及验收镜像均无残留。

## 真实浏览器入口

`consoles/integration-test/password-setup-notification-acceptance.mjs` 接入既有 Chrome/Fresh 产品验收。通过真实页面准备 Tenant/Subscription，停止本轮隔离项目的 SMTP 测试服务产生真实故障；确认激活后仅加速隔离数据库中已失败工作项的重试耗尽时间，不修改产品策略。合法重发失败后刷新取得原恢复动作，恢复 Mailpit，再继续原工作流并核对 Mailpit、Tenant、历史 Membership 和真实用量；包含其他管理员无接手动作、双语、刷新和无障碍检查。

本机第五轮证据目录为 `/tmp/issue177-chrome-evidence-r5`，包含 `acceptance-run.json` 与四张 `issue-177-*.png`。Chrome 153.0.8010.36；运行基点为 `029576057a0797782892831b07b8cb78299155dc`，`dirty=true` 对应本次实现、测试与文档。产品测试使用本次重新构建的 Console 制品。

本记录不替代远端当前提交 CI，也不自动关闭 #177、#170 或 #165。
