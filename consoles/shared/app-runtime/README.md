# `@saas-forge/app-runtime`

共享 Runtime Config、Bootstrap 与 Console 认证内核。该包从同 Origin 的
`/runtime-config.json` 加载并校验首版配置，在配置成功后为宿主固定的 `PLATFORM` 或 `TENANT`
Intent 创建每个页面 Realm 唯一的纯 TypeScript Runtime；不包含 React、路由、Design System、样式或
Remote 逻辑。

配置必须严格包含两个字段：

```json
{
  "schemaVersion": 1,
  "apiBaseUrl": "https://api.example.test"
}
```

`apiBaseUrl` 必须是无凭据、业务路径、查询参数和 Fragment 的绝对 HTTPS Origin。加载失败只返回
`CONFIG_NOT_FOUND`、`CONFIG_UNAVAILABLE`、`CONFIG_INVALID` 或 `API_ORIGIN_INVALID`，不会返回原始响应、
配置内容或异常。

认证 Runtime 只通过公共根入口暴露判别联合状态、认证 operation 和受控的正式类型化 API operation。
Access Token 与计算后的到期时间只保存在 Runtime 闭包内；消费方不能读取 Token、取得通用凭据型
`fetch`、覆盖 API Origin，或注入 Cookie、Origin、Fetch Metadata、Authorization 等安全请求头。

启动与认证状态的用户界面由 `@saas-forge/design-system` 和后续共享 React Shell 拥有。消费方只把本包的
安全状态映射到公共视觉组件，不能在本包增加 React 组件、CSS 或其他视觉实现。
