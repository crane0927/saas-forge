# OpenAPI 是生成 REST 代码的唯一来源

正式 OpenAPI 契约是服务端 Spring MVC 接口骨架、`sdk-core` Java REST Client 和控制台 TypeScript API Client 的唯一输入；生成物不提交，手写 Controller 只能实现其所属 operation 的生成接口且不得自行声明 HTTP 路由。每个 operation 以唯一 `x-saasforge-service` 标明领域服务归属，`tags` 只保留分组职责；这样在保留服务领域模型私有性的同时，让公开 HTTP 行为可重复生成并由构建防止实现漂移。
