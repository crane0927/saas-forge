# Maven 构建与制品发布

## 工具链基线

仓库唯一构建入口为 Maven Wrapper。Wrapper 固定 Maven 3.9.14，并通过 `distributionSha256Sum` 校验下载的发行包；本地与 CI 都使用 `./mvnw`。父 POM 的 Enforcer 只接受 Maven 3.9.14 以及 JDK 17、JDK 21。

所有 Java 源码统一以 `release=17` 编译。JDK 17 是最低构建与运行版本，JDK 17 和 JDK 21 都是正式支持的运行时；Pull Request 与普通 Push 必须分别在两个 JDK 上通过完整的：

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

## 父 POM 与版本管理

根 `pom.xml` 同时作为 Reactor 聚合器和全仓库构建父 POM，并继承 `spring-boot-starter-parent`：

- Spring Boot 管理其生态内的依赖和插件版本；
- 非 Spring Boot 第三方依赖由根 POM 的 `dependencyManagement` 和版本属性统一管理；
- 仓库内部依赖由根 POM 统一管理，子模块不声明内部依赖版本；
- `saas-forge-bom` 只向使用者导出 SDK 与 Starter 版本，不承担仓库构建管理；
- 子模块不得使用 `LATEST`、`RELEASE` 或版本区间；正式发布不得依赖任何 `SNAPSHOT`；
- Enforcer 在 `validate` 阶段检查工具链、重复依赖、动态版本、依赖收敛、Reactor 版本一致性和插件版本。

仓库使用 Maven CI-friendly `${revision}`。默认值为 `0.1.0-SNAPSHOT`；正式发布由标签提供不带 `SNAPSHOT` 的版本。Flatten Plugin 为安装和发布生成已解析 `${revision}` 的消费者 POM。

## 测试与覆盖率

- Surefire 在 `test` 阶段只运行 `*Test`；
- Failsafe 在 `integration-test` 与 `verify` 阶段运行 `*IT`；
- Testcontainers、数据库、Redis、Kafka 和跨模块契约验证使用 `*IT`；
- 浏览器端到端测试、性能测试和 ZAP 由独立 CI Job 负责，不并入 Maven 父 POM；
- JaCoCo 同时采集单元测试和集成测试覆盖率，由 `quality-gates` 生成 Reactor 聚合报告并执行门禁；
- 全仓行覆盖率不得低于 80%，分支覆盖率不得低于 70%；
- `iam-service`、`tenant-access-service`、`entitlement-service`、`saas-forge-sdk-auth`、`saas-forge-sdk-tenant`、`saas-forge-sdk-permission` 与 `saas-forge-sdk-quota` 的行覆盖率不得低于 90%；
- 生成代码和无业务逻辑的 `*Application` 启动入口不计入覆盖率；没有生产代码的空模块不阻断构建。

## Maven Central 发布

公开 Maven 坐标使用 `io.github.crane0927`；Java 包名继续使用 `io.saasforge.*`。原因见 [ADR 0012](adr/0012-maven-coordinates-use-github-namespace.md)。

Maven Central 发布白名单为：

- 根父 POM `saas-forge`；
- `saas-forge-bom`；
- 所有 `saas-forge-sdk-*` Java SDK；
- `saas-forge-spring-boot-starter`。

Gateway、领域服务、`quality-gates`、纯聚合模块及尚未确定打包契约的 OpenAPI、Protobuf、事件模块不得部署到 Maven Central。仓库不发布远程 `SNAPSHOT`。

受保护的 `vX.Y.Z` 标签触发 `.github/workflows/release.yml`。发布流程先在 JDK 17 和 JDK 21 上以 `X.Y.Z` 执行完整 `verify`，全部通过后才由 JDK 17 重新构建正式制品。Release Profile 附加 sources、Javadoc 和 GPG 签名，通过 Central Publisher Portal 自动公开并等待 `published` 结果。

GitHub Actions 需要配置以下 Secrets：

- `CENTRAL_USERNAME`
- `CENTRAL_PASSWORD`
- `MAVEN_GPG_PRIVATE_KEY`
- `MAVEN_GPG_PASSPHRASE`

发布构建以标签提交时间覆盖 `project.build.outputTimestamp`，并在 Job Summary 记录版本、提交 SHA、JDK、Maven 与 SDK/Starter JAR 的 SHA-256。正式发布只允许由 CI 执行，本地 `deploy` 不作为发布路径。
