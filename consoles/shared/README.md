# Shared frontend

这里只保留已经承担跨应用职责的共享前端包：`api-client` 是 Maven/OpenAPI Generator 唯一生成的 API Client，`app-runtime` 负责严格 Runtime Config 与可重试 Bootstrap。认证、CSRF、共享 HTTP Client 和业务 Remote 契约尚未实现，不在当前共享边界中预留空包。
