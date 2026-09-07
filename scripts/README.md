# 脚本

脚本只自动化已有的明确流程。

- `validate-nacos-config.sh`：校验四个环境的 Nacos 非敏感配置清单，或只校验传入的单个环境。
- `validate-local-compose-jwt.sh`：校验本地 Compose 是否向 IAM 注入 JWT 配置、只读挂载私钥，并在 `.env.example` 声明初始化变量。
- `initialize-local-iam-signing-key.sh`：显式生成 Git 忽略的本地 PKCS#8 RSA 私钥，并在迁移后的 IAM 数据库中初始化与其匹配的唯一 ACTIVE Signing Key 元数据。
- `local-development.sh frontend <start|status|stop> platform`：Issue #149 的 Platform 显式受管生命周期；旧无参数 `frontend` 不再兼容。其余 `setup|doctor|status|replace|restore` 命令保持既有五服务开发入口职责。
- `local-https-development.sh <start|status|stop> platform`：统一入口复用的受控 HTTPS 底层命令；另保留 `setup|hosts|trust-ca|doctor` 准备与诊断命令。脚本不会安装前端依赖、生成 API Client、修改系统配置或启动后端。
- `local-service-replacement.sh <doctor|replace|status|restore> <gateway|iam-service|tenant-access-service|entitlement-service|audit-service>`：统一入口复用的单目标生命周期命令。在不构建应用镜像、不删除卷的前提下，受控切换一个唯一健康的容器服务和对应本机 JVM；只读取受限 Secret，输出不包含其值，`replace` 失败时自动恢复选定容器。
- `verify-iam-local-replacement-e2e.sh`：在已运行的本地 Compose 开发栈中执行 IAM 本机替换、真实浏览器 Refresh 和容器恢复验收；结束时恢复标准拓扑并核对所有应用镜像标识未变化。
- `verify-iam-local-cross-service-e2e.sh`：使用只读 Platform Admin 凭据文件，在受控浏览器中验证 HTTPS Edge → Gateway → Entitlement → 本机 IAM 的 Platform Role gRPC 路径；首次运行会保留 `max_users` DRAFT Quota Definition。
- `verify-local-service-replacement-e2e.sh <gateway|iam-service|tenant-access-service|entitlement-service|audit-service>`：对一个目标执行容器→本机→容器验收；通过真实 Platform 登录表单、页面可见结果、正式浏览器 API 操作、控制台/网络错误、Nacos 单实例和应用镜像标识核验本机替换与恢复。
- `verify-local-development-matrix.sh`：依次验收五个目标，并核对每次恢复、HTTP localhost Origin 拒绝、最终完整 Compose 拓扑、应用镜像标识和数据卷集合。凭据文件必须包含当前正式密码；Tenant、Quota Definition 与 Audit 事实会按正式操作保留。
- `publish-nacos-config.sh <environment>`：使用独立配置发布身份将一个已校验的环境清单发布到同名 Nacos namespace。
- `verify-nacos-acl.sh`：针对临时或本地 Nacos 验证每个工作负载只能读取自己的配置，且不能发布配置。
