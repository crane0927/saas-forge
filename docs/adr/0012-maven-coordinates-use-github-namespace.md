# Maven 制品使用 GitHub 命名空间

公开 Maven 制品使用 `io.github.crane0927` groupId，因为项目无法控制 `saasforge.io` 的 DNS，不能为 Maven Central 验证 `io.saasforge` 命名空间。Java 包名继续保留 `io.saasforge.*` 以维持稳定的品牌命名；两者无需一致，未来不得仅为表面对齐而迁移 Java 包或恢复不可验证的 Maven 坐标。
