# saas-forge 部署设计

## 部署原则

`saas-forge` 应可独立运行。Server、控制台和业务服务可独立开发、部署、扩容和升级；Server 尽可能无状态，以支持水平扩容、负载均衡和高可用。

## 一体化体验部署

适用于快速体验、开发环境和中小规模系统：

```text
Docker Compose
├── saas-forge-server
├── saas-forge-console
├── database
└── redis
```

目标体验为执行：

```bash
docker compose up -d
```

后可得到 SaaS 平台基础服务、Platform Console 与 Tenant Console。

## 独立业务部署

生产环境推荐分离：

```text
SaaS Product
├── saas-forge-server
└── lis-server
      └── 通过 API / SDK 与 saas-forge-server 集成
```

二者独立开发、部署、扩容和升级。

## 演进方向与待补充项

首期使用 Docker 与 Docker Compose，后续考虑 Kubernetes 与 Helm。原始材料未定义生产网络拓扑、环境配置、容器镜像、配置管理、密钥注入、发布/回滚、备份恢复、监控告警和容量规划；这些需在部署详细设计中补充。
