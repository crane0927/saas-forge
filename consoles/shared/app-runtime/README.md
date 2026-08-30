# `@saas-forge/app-runtime`

共享 Runtime Config 与 Bootstrap 内核。该包只负责从同 Origin 的
`/runtime-config.json` 加载、校验首版配置，并将启动过程表示为可重试的类型化状态；不包含
React、路由、认证、HTTP Client、Design System 或 Remote 逻辑。

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

`@saas-forge/app-runtime/bootstrap.css` 提供加载、配置失败、致命错误和 `404` 表面的依赖无关基础样式。
消费方应使用 `main`、标题、段落和原生 `button` 等语义元素，并组合 `sf-runtime-surface`、
`sf-runtime-panel`、`sf-runtime-code` 与 `sf-runtime-action` 类名。样式只保证基本可读、键盘焦点、窄屏和
系统深浅色兼容，不定义产品主题。
