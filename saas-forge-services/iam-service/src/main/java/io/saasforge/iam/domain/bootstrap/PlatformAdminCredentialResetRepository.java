package io.saasforge.iam.domain.bootstrap;

import java.util.Optional;
import java.util.UUID;

/** Platform Admin 初始凭证重置的幂等事实与并发边界。 */
public interface PlatformAdminCredentialResetRepository {

    /** 串行化重置请求，确保每个新 requestId 只替换前一次已提交的初始凭证。 */
    void lockReset();

    Optional<PlatformAdminCredentialResetFact> findByRequestId(UUID resetRequestId);

    void create(PlatformAdminCredentialResetFact fact);
}
