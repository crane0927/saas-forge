# Protobuf contracts

内部同步通信使用版本化 Protobuf；契约类型只在服务边界映射为各服务自己的内部模型。新增字段必须使用新的 tag，既有 tag 不得复用、删除或改变语义；破坏性变化发布新的 package 主版本。

所有 v1 package 受已发布契约基线保护；质量门拒绝删除或改变既有 service、RPC、message、field tag、类型、名称或 `oneof` 归属。

## IAM Platform Role 校验

[Platform Authorization v1](iam/authorization/v1/platform_authorization.proto) 定义平台服务向 IAM 实时复核 Platform Role 的只读边界。请求只携带规范 UUIDv7 `identity_id` 与精确 `role_key`，响应只返回 `allowed`；调用必须携带有效 Service Access Token 和 `iam:platform-role:read` Scope。无效令牌、错误 Scope、非法输入或 IAM 不可用均失败关闭，调用端不得从 Gateway Header、Tenant Context 或 User Access Token 中推导角色。

## IAM Identity 幂等确保

[Identity Provisioning v1](iam/identity/v1/identity_provisioning.proto) 定义 Tenant Access 在配额副作用前创建或复用 IAM Identity 的写边界。`request_id` 必须是重试中保持稳定的 UUIDv7；调用必须使用保留的 Tenant Access Service Access Token 和 `iam:identity:write` Scope。IAM 按规范化邮箱去重，只返回 Identity ID 与稳定的凭证处置结论，不创建或重置 Credential。

[User Session Revocation v1](iam/session/v1/session_revocation.proto) 定义 Tenant Access 以稳定 UUIDv7 请求按 Membership 或 Tenant 建立 Revocation Fence、分批撤销 User Session，显式恢复原耗尽工作流，并按原撤销 generation 条件释放 Fence 的写边界。调用必须使用保留的 Tenant Access Service Access Token 和精确 `iam:sessions:write` Scope；正常未完成返回业务 `PENDING`，基础设施不可用使用 gRPC `UNAVAILABLE`，目标冲突或释放代际不匹配使用 `FAILED_PRECONDITION`。

## IAM ↔ Tenant Access Membership 校验

[Membership Validation v1](tenant_access/membership/v1/membership_validation.proto) 定义 Tenant Access 向 IAM 提供的通用、只读 Membership 即时校验。请求携带 `identity_id` 与 `membership_id`，仅在 Membership 属于该 Identity、处于启用状态且所属 Tenant 当前可访问时返回权威的 Membership 与 Tenant ID；否则返回无原因的 `membership_not_usable`。

首个调用场景是 Tenant 切换。允许结论不得缓存或复用；网络、超时或协议错误均由 IAM 失败关闭，且不得创建或保留新的 Tenant Context。服务通信使用 mTLS，W3C Trace Context 通过 gRPC metadata 传播，不进入消息字段。

同一 v1 文件中的 `AccessibleMembershipQueryService.ListAccessibleMemberships` 为 IAM 提供 Identity 当前可进入的 Membership 与最小 Tenant 展示信息。结果按 Tenant 展示名、Membership ID 稳定排序，最多返回 101 条；第 101 条只用于让调用方识别超过 100 条的结果集，不引入分页或 IAM 侧事实副本。
