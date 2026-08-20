package io.saasforge.iam.application.authentication;

public sealed interface LoginResult permits AccessTokenLoginResult, ContextSelectionLoginResult {
    String refreshToken();

    long refreshCookieMaxAgeSeconds();
}
