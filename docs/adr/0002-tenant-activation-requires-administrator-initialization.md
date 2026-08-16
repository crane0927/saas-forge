# Tenant 激活以管理员初始化成功为前提

新建 Tenant 初始状态为 `PENDING`，在初始 Tenant 管理员初始化成功前不允许登录、租户切换或业务访问。仅初始化成功允许 `PENDING` 转为 `ACTIVE`；初始化失败时保留 `PENDING` 以便安全重试，从而避免出现无管理员但已可访问的 Tenant。
