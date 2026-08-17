# Protobuf contracts

内部同步通信使用版本化 Protobuf；契约类型只在服务边界映射为各服务自己的内部模型。新增字段必须使用新的 tag，既有 tag 不得复用、删除或改变语义；破坏性变化发布新的 package 主版本。

## IAM ↔ Tenant Access Membership 校验

[Membership Validation v1](tenant_access/membership/v1/membership_validation.proto) 定义 Tenant Access 向 IAM 提供的通用、只读 Membership 即时校验。请求携带 `identity_id` 与 `membership_id`，仅在 Membership 属于该 Identity、处于启用状态且所属 Tenant 当前可访问时返回权威的 Membership 与 Tenant ID；否则返回无原因的 `membership_not_usable`。

首个调用场景是 Tenant 切换。允许结论不得缓存或复用；网络、超时或协议错误均由 IAM 失败关闭，且不得创建或保留新的 Tenant Context。服务通信使用 mTLS，W3C Trace Context 通过 gRPC metadata 传播，不进入消息字段。
