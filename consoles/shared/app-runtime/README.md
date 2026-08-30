# `@saas-forge/app-runtime`

共享 Runtime Config 与 Bootstrap 内核。该包只负责从同 Origin 的
`/runtime-config.json` 加载、校验首版配置，并将启动过程表示为可重试的类型化状态；不包含
React、路由、认证、HTTP Client、Design System、样式或 Remote 逻辑。

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

启动状态的用户界面由 `@saas-forge/design-system` 拥有。消费方只把本包的状态映射到公共视觉组件，不能在
本包增加 React 组件、CSS 或其他视觉实现。
