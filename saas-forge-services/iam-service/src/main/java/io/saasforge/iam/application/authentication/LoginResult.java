package io.saasforge.iam.application.authentication;

public sealed interface LoginResult permits AccessTokenLoginResult, ContextSelectionLoginResult,
        InitialPasswordChangeLoginResult {
    String refreshToken();

    long refreshCookieMaxAgeSeconds();
}
