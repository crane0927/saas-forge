## Agent skills

### 后端版本管理

- 后端依赖、仓库内部模块和 Maven 插件的版本统一由根项目 `pom.xml` 管理；Spring Boot 生态版本通过根 POM 继承的 `spring-boot-starter-parent` 管理。
- 子模块只声明所需依赖或插件，不得声明 `<version>`；新增非 Spring Boot 第三方版本时，必须在根 POM 的版本属性与 `dependencyManagement` 中登记。
- 如果引入某项依赖能够大幅减少实现工作量，可以向用户提出引入建议；未经用户确认不得直接引入。

### 配置与 Nacos

- Nacos 仅管理按环境变化的非敏感运行配置，例如服务专属的业务重试/租约/超时策略、Outbox 参数、JWT issuer 与 TTL、登录保护策略，以及 TLS 启用策略和 SSL Bundle 名称。认证、CORS、TLS 等安全边界写入 Nacos 时必须保持 `refreshEnabled=false`，只允许受控发布和滚动生效。
- `application.yaml`、Helm values 或环境变量保留应用标识和 Nacos 启动前必需的配置：`spring.config.import`、Nacos 地址/namespace/客户端 TLS、工作负载身份，以及不能由 Nacos 管理的部署拓扑参数。MyBatis 映射、Jackson 行为、Flyway 开关等制品固定配置不迁入 Nacos。
- 密码、Token、数据库/Redis/Kafka 凭据、OAuth Client Secret、JWT/KMS 私钥、mTLS 证书与私钥不得写入 Nacos 或仓库普通配置；只能由外部密钥管理、Secret 或受限凭据文件注入。Nacos 中可配置 SSL Bundle 名称，不能配置其证书材料。
- `browser.rootDomain` 及由其推导的 CORS/Cookie 安全边界属于部署配置，不迁入 Nacos；服务 HTTP/gRPC 实例地址不得仅以 Nacos YAML 中的静态地址替代服务发现。
- Nacos 配置按环境 namespace 和应用专属资源维护，当前不得新增共享资源。跨服务必须一致的非敏感值应分别写入各自资源，并增加或更新一致性校验；迁移前必须先确认每个环境的实际值，不能臆造 staging/prod 值。
- 修改 `deploy/nacos/<environment>/` 后必须递增对应 `configuration-revision`，运行 `bash scripts/validate-nacos-config.sh` 与相关服务验证；发布只能通过受控发布流程，Console 应急变更必须回写 Git。

### Issue tracker

问题与 PRD 通过本仓库的 GitHub Issues 跟踪。详见 `docs/agents/issue-tracker.md`。

### Domain docs

本仓库采用多上下文领域文档布局，并以 `CONTEXT-MAP.md` 为入口。详见 `docs/agents/domain.md`。
