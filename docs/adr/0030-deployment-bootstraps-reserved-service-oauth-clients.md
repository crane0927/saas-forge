# 部署 Job 创建保留的服务 OAuth Client

IAM 同一制品提供独立、显式的服务身份 bootstrap 操作，由 Flyway 完成后的部署前置一次性 Job 从外部挂载的 Secret 文件读取三个保留服务的固定 UUIDv7 `client_id`、256 位随机 Secret 与精确 Scope，并在 IAM 单一事务中创建 `iam-service`、`tenant-access-service`、`entitlement-service` 的 OAuth Client。每个运行服务只获得自己的 ID 与 Secret；正常 IAM 启动、Flyway、源码、镜像、Compose 默认值和 Nacos 普通配置都不创建或保存这些 Secret。重复执行只有在 ID、Secret 摘要与 Scope 完全一致时幂等成功，任何差异都失败并转人工处理；后续轮换使用正式 Client 管理生命周期，不复用 bootstrap 创建逻辑。这样既能在公共 Client 管理完成前建立最小可信跨服务调用，又避免默认 Secret、自动覆盖和运行时过度持权。

正式 Client 管理生命周期落地后，[ADR 0033](0033-oauth-client-management-uses-immediate-revocation-and-replacement-recovery.md) 收窄上述“完全一致”重放规则：bootstrap 对已存在的 ACTIVE 保留 Client 只读校验固定身份、Scope 与挂载 Secret 是否匹配任意当前有效摘要，以允许正式轮换后的部署；它仍不得修改、复活或替代 Client。已吊销保留 Client 只能由受控 Replacement Job 创建新身份。
