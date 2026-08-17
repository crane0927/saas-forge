# Membership 与事件契约使用显式主版本和失败关闭语义

IAM 通过 Tenant Access 所有的版本化 Protobuf 同步校验 Membership，并且不得缓存允许结果；不可用或调用失败均不能建立新的 Tenant Context。跨服务事实使用 CloudEvents 1.0 JSON 信封，事件 ID 在 Outbox 创建后保持稳定，类型名承载主版本；审计与缓存失效分别只发布审计记录引用和逻辑失效范围，从而避免将安全判断、审计内容或物理缓存拓扑耦合到 Kafka。
