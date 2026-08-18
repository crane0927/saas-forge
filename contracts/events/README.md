# Event contracts

事件只表达来源服务已提交的事实，不能作为跨服务命令或同步流程的成功依据。发布使用 Transactional Outbox，Kafka 至少一次交付；消费者按 CloudEvents `id` 幂等。

实际业务切片的本地 Outbox、发布器、消费者和去重表必须遵循[Transactional Outbox 工程约定](transactional-outbox.md)。每个实际实现事件还必须先登记到[事件工程注册表](engineering-registry.json)，其字段由[v1 Schema](engineering-registry.schema.json)约束；当前没有业务切片实现，注册表因此为空。

## 统一信封

全部事件采用 CloudEvents 1.0 structured JSON，并通过 [CloudEvents JSON Envelope v1](cloudevents-envelope.v1.schema.json) 校验。`specversion` 固定为 `1.0`，`id` 是在 Outbox 创建时分配的 UUIDv7，重投和恢复时不得改变；新事实才产生新 ID。`source` 使用固定服务 URN（`urn:saasforge:<service>`），`type` 使用带主版本的 `com.saasforge.*.vN`，`time` 为事实提交时间的 RFC 3339 UTC 值，`datacontenttype` 固定为 `application/json`。`traceId` 是唯一允许的扩展属性，且仅在已有 W3C Trace Context 时出现。

`subject` 和 `dataschema` 可选。事件与其 `data` 只能包含事件 schema 明确白名单中的内部 ID、状态和时间；不得包含密码、邮箱原文、Invitation 激活令牌、Access/Refresh Token、Client Secret、Cookie、物理缓存 Key 或缓存节点信息。

## 版本规则

`type` 末尾的 `.vN` 是事件主版本。同一版本只能新增可选 `data` 字段；删除字段、改变字段类型或语义、收紧枚举、增加必填字段均为破坏性变更，必须发布新的 `type` 主版本。迁移期间生产者双发旧、新类型，消费者兼容两版；旧版本只在全部消费者完成切换后停发。

## 审计与缓存失效

| 类型 | 生产者 | `data` | Schema |
|---|---|---|---|
| `com.saasforge.audit.recorded.v1` | Audit | `auditRecordId`、`sourceEventId`、`recordedAt`，可选 `tenantId` | [Audit Record Stored v1](audit-recorded.v1.schema.json) |
| `com.saasforge.cache.invalidated.v1` | 对应缓存域的权威服务 | `cacheDomain`、`scope`，以及范围匹配的 `tenantId` / `membershipId` | [Logical Cache Invalidated v1](cache-invalidated.v1.schema.json) |

`audit.recorded.v1` 仅说明 Audit 已追加保存一条 Audit Record；完整审计内容必须通过 Audit 服务的受权查询取得。缓存失效只表达逻辑缓存域（`authorization` 或 `entitlement`）及范围（`MEMBERSHIP`、`TENANT` 或 `GLOBAL`）；消费者自行清理本地或业务缓存，未命中、过期、失效消息或缓存不可用时回源权威接口，不得把它当作领域真相。

`authorization` 缓存失效由 Tenant Access 发布，`entitlement` 缓存失效由 Entitlement 发布；其他缓存域不是 v1 契约的一部分。

Tenant、Invitation、Membership、Tenant 切换、会话撤销与 `max_users` 的首批事件及负载白名单见 [Tenant Access 跨服务工作流](tenant-access-workflows.md)。

全部 `*.v1.schema.json`（包括 CloudEvents 信封）受已发布契约基线保护；质量门拒绝删除事件字段、改变类型或常量、增加必填字段、收紧枚举和 schema 约束。
