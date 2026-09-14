package io.saasforge.iam.application.authentication;

public final class TokenRevocationStatusUnavailableException extends RuntimeException {
    public static final String CODE = "TOKEN_REVOCATION_STATUS_UNAVAILABLE";

    public TokenRevocationStatusUnavailableException() {
        super("Token 撤销状态当前无法安全判定");
    }
}
