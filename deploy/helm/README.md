# Helm

生产应用 Chart 只接入外部提供的有状态依赖，不部署数据库、缓存、消息队列、对象存储或 Nacos Server。本仓库当前不包含完整应用 Chart；Nacos 的生产接入边界由 [nacos-production-contract.yaml](nacos-production-contract.yaml) 固定，供后续 Chart 按同一接口渲染。

Nacos 必须是外部高可用集群或其高可用接入端点。应用工作负载从外部密钥管理服务同步或注入的 Kubernetes Secret 读取各自的用户名和密码；Chart 只能以 `secretKeyRef` 引用，禁止在 values、ConfigMap、Nacos 清单或镜像中保存凭据。每个 Secret 必须提供 `username` 与 `password` 两个键，并映射为对应应用的 `NACOS_<APPLICATION>_USERNAME`、`NACOS_<APPLICATION>_PASSWORD` 环境变量。

生产渲染必须将以下非敏感连接参数注入所有五个应用：

| 环境变量 | 来源 | 约束 |
| --- | --- | --- |
| `NACOS_SERVER_ADDR` | `nacos.serverAddress` | 外部集群 HTTPS 地址；不得使用 Compose 服务名或静态业务服务地址。 |
| `NACOS_NAMESPACE` | `nacos.namespace` | 生产为 `prod`。 |
| `NACOS_TLS_ENABLED` | `nacos.tls.enabled` | 必须为 `true`，供 Config Client 启用 TLS。 |
| `JAVA_TOOL_OPTIONS` | `nacos.tls.namingJavaToolOptions` | 必须包含 `-Dcom.alibaba.nacos.client.naming.tls.enable=true`，供 Naming Client 启用 TLS。 |

该接口不包含 Nacos Helm dependency、Nacos StatefulSet、Nacos Service 或 Nacos Ingress。运行 `bash scripts/validate-nacos-production-contract.sh` 可校验这份生产接口及五个应用的 TLS 配置入口。

非敏感配置继续以 [`../nacos/README.md`](../nacos/README.md) 所述的 Git 受控清单和受保护环境发布；普通配置更新按滚动发布生效，公开路由白名单、认证与 CORS、TLS、数据库连接和迁移不允许热更新。
