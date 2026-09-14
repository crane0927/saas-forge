package io.saas.forge.iam.application.authentication;

public record InitialPasswordChangeLoginResult(
        String refreshToken,
        long refreshCookieMaxAgeSeconds) implements LoginResult {
}
