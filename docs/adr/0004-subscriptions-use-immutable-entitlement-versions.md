# Subscription 使用不可变权益版本

任一 Tenant 在任一时刻至多有一个授予权益的 Subscription。套餐变更和自然到期后的重新订阅均在同一事务中将旧版本标记为 `SUPERSEDED`、创建立即生效的新版本并写入新的不可变权益快照；不原地修改旧版本，从而保留完整审计历史并避免双重权益窗口。

本文描述的是阶段 5 的目标模型，尚未实现：当前 `subscriptions` 表以 `UNIQUE (tenant_id)` 与 `CHECK (subscription_status = 'ACTIVE')` 只表达每个 Tenant 首个立即生效的 Subscription，不存在 `SUPERSEDED` 状态，也没有不可变权益快照表，额度上限实时从 `plan_quotas` 读取。套餐变更与自然到期后的重新订阅按 [MVP 开发计划](../16-mvp-development-plan.md) 第 5 阶段实施；在此之前不得假定版本化与快照已存在。
