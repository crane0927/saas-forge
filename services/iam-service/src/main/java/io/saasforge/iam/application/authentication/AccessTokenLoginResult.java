package io.saasforge.iam.application.authentication;

public record AccessTokenLoginResult(
        IssuedAccessToken accessToken,
        String refreshToken,
        long refreshCookieMaxAgeSeconds) implements LoginResult {
}
