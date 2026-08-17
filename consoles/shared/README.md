# Shared frontend

预留给经审查的控制台共享前端代码。`api-client` 是唯一的 OpenAPI 生成 API Client；Shell 以手写适配层提供认证、CSRF 与统一错误处理，业务 Remote 不得直接管理 Token。
