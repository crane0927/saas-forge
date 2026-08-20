# 登录显式选择 Platform 或 Tenant 上下文

邮箱密码登录以可选的 `contextType` 表达 Login Context Intent，枚举仅为 `PLATFORM`、`TENANT`，缺省为 `TENANT`，且不接受 Tenant 或 Membership ID。IAM 根据该意图校验 Platform Role 或 Accessible Membership，而不根据 Origin、Host 或控制台类型隐式推断；这样同一 Identity 同时拥有 Platform Role 和多个 Tenant Membership 时仍有确定行为，也不会把浏览器部署拓扑变成认证授权输入。该字段保持可选以兼容已冻结的 v1 请求，Platform Console 必须显式发送 `PLATFORM`。
