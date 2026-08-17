## Agent skills

### 后端版本管理

- 后端依赖、仓库内部模块和 Maven 插件的版本统一由根项目 `pom.xml` 管理；Spring Boot 生态版本通过根 POM 继承的 `spring-boot-starter-parent` 管理。
- 子模块只声明所需依赖或插件，不得声明 `<version>`；新增非 Spring Boot 第三方版本时，必须在根 POM 的版本属性与 `dependencyManagement` 中登记。

### Issue tracker

问题与 PRD 通过本仓库的 GitHub Issues 跟踪。详见 `docs/agents/issue-tracker.md`。

### Domain docs

本仓库采用多上下文领域文档布局，并以 `CONTEXT-MAP.md` 为入口。详见 `docs/agents/domain.md`。
