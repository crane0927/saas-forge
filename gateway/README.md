# Gateway HTTP 验收

Gateway 当前切片以受控下游替身执行外部 HTTP 验收。运行：

```bash
mvn -am -pl gateway test
```

`GatewayJwksRouteTest` 验证 OpenAPI v1 中每个当前 operation 的服务归属、方法、路径、查询参数、允许的请求头、请求体和成功响应透传；同时验证 Trace Context 继续或新建，以及客户端提供的转发头不会抵达下游。`GatewayProblemDetailsTest` 验证白名单之外的路由、未声明方法、合格和不合格的下游 Problem Details、连接失败及超时。`GatewayTargetsPropertiesTest` 验证缺失或非法目标配置会使应用启动失败。

完整构建还会执行 `quality-gates` 中的 OpenAPI v1 兼容性检查和仓库标准检查。

## 非本切片范围

本验收切片不实现或验收 JWT 验证、限流、CORS、CSRF、Cookie 策略、受信代理边界，或任何领域服务的业务闭环；这些责任仍由后续安全和领域切片分别定义。
