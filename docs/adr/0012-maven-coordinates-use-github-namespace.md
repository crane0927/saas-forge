# Maven 制品使用 GitHub 命名空间

公开 Maven 制品使用 `io.github.crane0927` groupId，通过项目维护者可验证的 GitHub 命名空间发布，不依赖品牌域名的 DNS 控制权。Maven 坐标与 Java 包名无需一致，不得恢复不可验证的 Maven 坐标。

本 ADR 原先保留旧 Java 包名的规则已由 [ADR 0048](0048-java-package-uses-saas-forge-segments.md) 替代；Java 包名统一为 `io.saas.forge.*`，上述 Maven groupId 决策继续有效。
