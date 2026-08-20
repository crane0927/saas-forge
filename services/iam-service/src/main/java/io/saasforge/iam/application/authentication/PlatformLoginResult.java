package io.saasforge.iam.application.authentication;

public record PlatformLoginResult(IssuedAccessToken accessToken, String refreshToken, long refreshCookieMaxAgeSeconds) {
}
