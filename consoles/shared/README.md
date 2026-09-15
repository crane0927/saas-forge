# Console 共享模块

- `api-client`：从正式 OpenAPI 生成的无状态类型化 Client。
- `app-runtime`：认证、会话协同、业务调用及原操作恢复；不依赖 UI 框架。
- `admin`：锁定的 Soybean Admin Element Plus 布局、认证界面、受控品牌和路由退出保护。
- `i18n`：语言注册表、ICU 消息与格式化。

Console 通过 Runtime 调用正式 API；凭据留在 Runtime 私有内存。业务页面直接使用 Element Plus。Remote 接收宿主的语言并继承主题，不拥有认证、品牌解析或浏览器偏好存储。
