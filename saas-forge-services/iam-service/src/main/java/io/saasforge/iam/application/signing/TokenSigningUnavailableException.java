package io.saasforge.iam.application.signing;

/** 运行时签名失败的稳定应用错误。 */
public final class TokenSigningUnavailableException extends RuntimeException {

    public static final String CODE = "TOKEN_SIGNING_UNAVAILABLE";

    public TokenSigningUnavailableException(Throwable cause) {
        super("JWT signing is temporarily unavailable.", cause);
    }
}
