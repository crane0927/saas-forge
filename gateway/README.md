# Gateway HTTP 验收

Gateway 当前切片以受控下游替身执行外部 HTTP 验收。运行：

```bash
mvn -am -pl gateway test
```

`GatewayJwksRouteTest` 验证 OpenAPI v1 中每个当前 operation 会通过所属服务的发现实例处理，并保留方法、路径、查询参数、允许的请求头、请求体和成功响应透传；同时验证任一所属服务没有健康实例时返回稳定的 `503`、Trace Context 继续或新建，以及客户端提供的转发头不会抵达下游。`GatewayProblemDetailsTest` 验证白名单之外的路由、未声明方法、合格和不合格的下游 Problem Details、连接失败及超时。

完整构建还会执行 `quality-gates` 中的 OpenAPI v1 兼容性检查和仓库标准检查。

## 非本切片范围

本验收切片不实现或验收限流、受信代理边界，或任何领域服务的业务闭环；这些责任仍由后续安全和领域切片分别定义。

Gateway 在构建时从 OpenAPI v1 生成公开路由及 `UserBearerAuth` 分类：required 路由必须携带有效 Token，anonymous 路由不校验 Token。optional 的登出路由会校验已提供的 Token；Token 无效或已撤销时仍转发 IAM，以便清理 Refresh Cookie，IAM 只会在 Token 有效时执行 `jti` 撤销。验签后会检查 IAM Redis Revocation Index 中的 `jti`、`kid`、Tenant Fence 与 Membership Fence；索引未就绪或 Redis 不可用时返回 `503 TOKEN_REVOCATION_STATUS_UNAVAILABLE`，不会转发下游。
