package io.saasforge.iam.domain.identity;

import java.util.Optional;
import java.util.UUID;

/** Identity 确保请求的幂等事实与并发边界。 */
public interface IdentityProvisioningRepository {

    /** 只串行化同一调用方的同一 requestId，不阻塞无关请求。 */
    void lockRequest(UUID callerClientId, UUID requestId);

    Optional<IdentityProvisioningFact> find(UUID callerClientId, UUID requestId);

    void create(IdentityProvisioningFact fact);
}
