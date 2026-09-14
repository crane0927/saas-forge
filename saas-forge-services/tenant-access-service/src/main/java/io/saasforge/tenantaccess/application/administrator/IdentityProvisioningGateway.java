package io.saasforge.tenantaccess.application.administrator;

import java.util.UUID;

public interface IdentityProvisioningGateway {
    Result ensure(UUID requestId, String email, String displayName);

    record Result(UUID identityId, IdentityCredentialDisposition credentialDisposition) {
        public Result {
            if (identityId == null || identityId.version() != 7 || credentialDisposition == null) {
                throw new IllegalArgumentException("Identity Provisioning 结果不合法");
            }
        }
    }
}
