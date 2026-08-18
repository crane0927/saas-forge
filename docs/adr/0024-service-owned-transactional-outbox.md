# 服务拥有 Transactional Outbox 与消费去重

平台以服务本地 Transactional Outbox、Kafka 至少一次交付和服务本地按 `(consumer_name, event_id)` 去重实现可靠事实传播；发布器使用可接管租约与原事件 ID 重投，消费者失败持久化隔离后受控重放。我们不引入共享 Outbox、全局去重表或共享持久化实现：这些会跨越领域数据所有权并把服务的迁移、恢复和留存策略耦合在一起；跨服务共享的仅是版本化事件 schema、工程注册表及其运行契约。
