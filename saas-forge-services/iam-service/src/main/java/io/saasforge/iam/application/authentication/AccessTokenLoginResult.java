package io.saasforge.iam.application.authentication;

public record AccessTokenLoginResult(
        IssuedAccessToken accessToken,
        String refreshToken,
        long refreshCookieMaxAgeSeconds,
        TenantAuthenticationContextSnapshot tenantContext) implements LoginResult {

    public AccessTokenLoginResult(
            IssuedAccessToken accessToken, String refreshToken, long refreshCookieMaxAgeSeconds) {
        this(accessToken, refreshToken, refreshCookieMaxAgeSeconds, null);
    }
}
