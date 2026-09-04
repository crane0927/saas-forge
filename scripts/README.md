# 脚本

脚本只自动化已有的明确流程。

- `validate-nacos-config.sh`：校验四个环境的 Nacos 非敏感配置清单，或只校验传入的单个环境。
- `validate-local-compose-jwt.sh`：校验本地 Compose 是否向 IAM 注入 JWT 配置、只读挂载私钥，并在 `.env.example` 声明初始化变量。
- `initialize-local-iam-signing-key.sh`：显式生成 Git 忽略的本地 PKCS#8 RSA 私钥，并在迁移后的 IAM 数据库中初始化与其匹配的唯一 ACTIVE Signing Key 元数据。
- `local-https-development.sh <setup|hosts|trust-ca|doctor|start>`：为 macOS Docker Desktop 的 Platform Console 日常开发准备受控 HTTPS 入口。`hosts` 与 `trust-ca` 在修改系统前必须在交互终端输入明确授权；脚本不会安装前端依赖或改写锁文件。
- `local-service-replacement.sh <replace|status|restore> iam-service`：在不构建应用镜像、不删除卷的前提下，将唯一健康的容器 IAM 与本机 JVM 受控切换。保留 HTTP `8081`，并将本机 IAM gRPC 固定为 `9091`；替换期间仅重建 Tenant Access、Entitlement，使其通过 `host.docker.internal` 访问本机 IAM，恢复时自动回到容器服务名。工具只读取受限 Secret，并用现有 Gateway 发现身份只读判断 Nacos 实例；`replace` 失败时自动恢复容器 IAM。
- `verify-iam-local-replacement-e2e.sh`：在已运行的本地 Compose 开发栈中执行 IAM 本机替换、真实浏览器 Refresh 和容器恢复验收；结束时恢复标准拓扑并核对所有应用镜像标识未变化。
- `verify-iam-local-cross-service-e2e.sh`：使用只读 Platform Admin 凭据文件，在受控浏览器中验证 HTTPS Edge → Gateway → Entitlement → 本机 IAM 的 Platform Role gRPC 路径；首次运行会保留 `max_users` DRAFT Quota Definition。
- `publish-nacos-config.sh <environment>`：使用独立配置发布身份将一个已校验的环境清单发布到同名 Nacos namespace。
- `verify-nacos-acl.sh`：针对临时或本地 Nacos 验证每个工作负载只能读取自己的配置，且不能发布配置。
