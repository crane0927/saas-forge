# Invitation 激活使用同步补偿 Saga

Tenant Access 拥有 Invitation 状态迁移。它锁定并验证待处理 Invitation，为一次激活尝试分配稳定的 `consume` 与 `release` UUIDv7，执行 `max_users` 扣减，调用 IAM 确认 Identity 并仅在其尚无凭据时建立凭据，再以本地事务创建启用 Membership 并接受 Invitation；扣减后的失败以该尝试的 `release` UUIDv7 持久化补偿，Invitation 保持 `PENDING`。Identity 与既有凭据不作为补偿对象。同一未完成尝试的重试复用这对 ID；补偿成功后重新激活会生成新的尝试与新的 ID，确保再次占用额度。若补偿尚未完成，拒绝新的激活尝试并返回 `503` / `INVITATION_ACTIVATION_COMPENSATING` 与 `Retry-After`。此模式在不使用分布式事务的前提下，保留即时激活结论和可重试计量。
