package io.saasforge.iam.application.bootstrap;

import io.saasforge.iam.domain.client.OAuthScope;
import java.util.Set;

/** 部署引导允许创建的三个固定内部服务身份。 */
public enum ReservedServiceClient {
    IAM("iam-service", Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ)),
    TENANT_ACCESS("tenant-access-service", Set.of(
            OAuthScope.IAM_IDENTITY_WRITE,
            OAuthScope.IAM_PASSWORD_SETUP_WRITE,
            OAuthScope.IAM_PLATFORM_ROLE_READ,
            OAuthScope.ENTITLEMENT_QUOTA_WRITE)),
    ENTITLEMENT("entitlement-service", Set.of(
            OAuthScope.TENANT_ACCESS_TENANT_READ,
            OAuthScope.IAM_PLATFORM_ROLE_READ));

    private final String displayName;
    private final Set<OAuthScope> allowedScopes;

    ReservedServiceClient(String displayName, Set<OAuthScope> allowedScopes) {
        this.displayName = displayName;
        this.allowedScopes = Set.copyOf(allowedScopes);
    }

    public String displayName() {
        return displayName;
    }

    public Set<OAuthScope> allowedScopes() {
        return allowedScopes;
    }
}
