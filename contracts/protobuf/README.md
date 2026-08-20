# Protobuf contracts

内部同步通信使用版本化 Protobuf；契约类型只在服务边界映射为各服务自己的内部模型。新增字段必须使用新的 tag，既有 tag 不得复用、删除或改变语义；破坏性变化发布新的 package 主版本。

所有 v1 package 受已发布契约基线保护；质量门拒绝删除或改变既有 service、RPC、message、field tag、类型、名称或 `oneof` 归属。

## IAM ↔ Tenant Access Membership 校验

[Membership Validation v1](tenant_access/membership/v1/membership_validation.proto) 定义 Tenant Access 向 IAM 提供的通用、只读 Membership 即时校验。请求携带 `identity_id` 与 `membership_id`，仅在 Membership 属于该 Identity、处于启用状态且所属 Tenant 当前可访问时返回权威的 Membership 与 Tenant ID；否则返回无原因的 `membership_not_usable`。

首个调用场景是 Tenant 切换。允许结论不得缓存或复用；网络、超时或协议错误均由 IAM 失败关闭，且不得创建或保留新的 Tenant Context。服务通信使用 mTLS，W3C Trace Context 通过 gRPC metadata 传播，不进入消息字段。

同一 v1 文件中的 `AccessibleMembershipQueryService.ListAccessibleMemberships` 为 IAM 提供 Identity 当前可进入的 Membership 与最小 Tenant 展示信息。结果按 Tenant 展示名、Membership ID 稳定排序，最多返回 101 条；第 101 条只用于让调用方识别超过 100 条的结果集，不引入分页或 IAM 侧事实副本。
