package io.saasforge.iam.application.authentication;

public record InitialPasswordChangeLoginResult(
        String refreshToken,
        long refreshCookieMaxAgeSeconds) implements LoginResult {
}
