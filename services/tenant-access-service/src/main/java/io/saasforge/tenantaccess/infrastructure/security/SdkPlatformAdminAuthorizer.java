package io.saasforge.tenantaccess.infrastructure.security;

import io.saasforge.sdk.auth.PlatformRequestAuthorizer;
import io.saasforge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import java.util.UUID;

public final class SdkPlatformAdminAuthorizer implements PlatformAdminAuthorizer {
    private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    private final PlatformRequestAuthorizer delegate;

    public SdkPlatformAdminAuthorizer(PlatformRequestAuthorizer delegate) {
        this.delegate = delegate;
    }

    @Override
    public UUID authorize(String authorization) {
        return delegate.authorize(authorization, PLATFORM_ADMIN);
    }
}
