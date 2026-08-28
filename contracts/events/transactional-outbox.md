# Transactional Outbox 工程约定

本约定适用于平台服务的已提交事实事件。当前 IAM、Tenant Access与 Entitlement已在各自业务切片实现本地 Outbox和发布器；服务首次消费事件时，仍必须在所属服务内同时落地本地去重、隔离迁移、实现和测试。不得建立共享 Outbox、全局消费去重表、共享持久化实体、Mapper 或迁移。

## 注册、边界与事件快照

实现事件前，先在 [事件工程注册表](engineering-registry.json)登记版本化 `type`、生产服务、`source`、包含 CloudEvents 信封与 payload 的独立事件 schema、生产者 topic、`orderingKey` 与允许的消费者。注册表必须符合 [v1 Schema](engineering-registry.schema.json)。topic 固定为 `saasforge.<environment>.<producer-service>.events`；每个消费者的隔离 topic 由该消费者服务拥有，不是跨服务死信总线。

业务事务在提交领域事实时，同一事务写入服务本地的不可变 Outbox 记录和完整 CloudEvents JSON 快照。记录至少保存稳定事件 ID、事实提交时间、topic、`orderingKey`、原始 `traceId`（如有）、payload、发布领取/重试状态和发布完成时间。发布器只能发送该快照，不得在重试时查询当前领域数据、重新序列化 payload 或创建新事件 ID。Outbox 是不可变事实记录，不使用 `updated_at`；是否属于 Tenant 范围由记录语义决定，不得为形式统一伪造 `tenant_id`。

`traceId` 保持既有 v1 兼容语义：入口创建或延续 W3C Trace Context 时，创建 Outbox 的事务固化当前值，发布、重试和消费原样传递；无业务请求上下文的定时任务事件可以省略。发布器不得为旧事件补写或改写 `traceId`。

## 发布器

每个服务实例在进程内运行后台发布器。多个实例通过服务自身数据库的领取租约并发领取待发布记录；租约到期后其他实例可以接管。Kafka 使用 `acks=all`，仅在收到确认后标记记录已发布。发送超时或进程在确认前故障均视为结果未知，继续以原事件 ID 重投，因此交付语义是至少一次。

发布器按 `orderingKey` 设置 Kafka record key，同一生产服务且同键的较早未发布记录不得被越过；不同键不承诺顺序，也不引入全局排序。不可用或发送失败时，持久化尝试次数、最近失败摘要与下次重试时间，使用带抖动且有上限的指数退避持续重试。达到告警阈值只告警，不隔离或丢弃已提交事实。

Kafka ACL 只允许服务运行身份写入自己的事件 topic、读取注册表允许的生产者 topic；隔离 topic 只允许所属消费者写入，以及受控运维重放身份读取。禁止通配写入、跨服务写入和消费未登记 topic。

## 消费、隔离与保留

消费者先校验 Kafka topic/ACL、允许的 `source` 与 `type`、CloudEvents 信封和注册的 payload schema，再执行业务处理。每个消费者在自己的服务数据库内以 `(consumer_name, event_id)` 唯一约束去重；插入成功去重记录和全部业务副作用必须在同一本地事务提交，随后才确认 Kafka。相同事件可被同一服务的不同 `consumer_name` 各处理一次。

可重试失败在受控阈值内重试。超过阈值，消费者持久化隔离记录（含 `consumer_name`、`event_id`、失败类别、首次/最近失败时间），告警并确认原消息；不允许静默丢弃。人工重放只能使用原事件 ID，且必须保留隔离记录的处置轨迹。校验失败同样不得产生业务副作用。

已发布 Outbox 与成功消费去重记录至少保留到对应 Kafka topic 的最长保留期结束；隔离记录在人工处置完成前不清理。超过该窗口的历史恢复必须走受控重建/重放流程，显式重置目标投影或迁移去重状态，不能把旧消息直接投回正常消费者。

## 可观测性与验证

服务必须暴露 Outbox 最早待发布年龄、待发布数量、领取租约到期/重试次数、发布成功/失败与耗时；消费者必须暴露处理延迟、重复命中、校验拒绝、隔离数量与最早隔离年龄。告警阈值由部署配置管理。日志只记录事件 ID、类型、topic、消费者名、尝试次数和 `traceId`，不得记录 payload。

每个首个实现至少以 PostgreSQL 与 Kafka 的集成测试验证：领域事务与 Outbox 的原子提交、确认前故障重投、租约接管、同键顺序、重复事件不重复副作用、不同消费者独立处理、校验拒绝、隔离和同 ID 重放，以及 `traceId` 的端到端保留。
