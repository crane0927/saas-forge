# Tenant 到期是派生访问结果

Tenant 持久化 `PENDING`、`ACTIVE`、`SUSPENDED`、`CLOSED` 四种生命周期状态，并以可空的 `expiresAt` 表示有效期。`EXPIRED` 由访问时刻与 `expiresAt` 派生，不写入 Tenant 状态；这样不依赖定时任务及时变更状态，也不会将人工冻结与时间到期混为同一生命周期语义。
