package io.saasforge.iam.domain.bootstrap;

import java.util.Optional;

/** Platform Admin 单例引导事实及其并发边界。 */
public interface PlatformAdminBootstrapRepository {

    /** 串行化首次状态检查与创建，避免并发 Job 各自观察到空状态。 */
    void lockInitialization();

    Optional<PlatformAdminBootstrapState> findState();

    /** 没有幂等事实时，任何既有初始凭据或 PLATFORM_ADMIN 授予都必须转人工处理。 */
    boolean hasUntrackedBootstrapState();

    void create(PlatformAdminBootstrapFact fact);
}
