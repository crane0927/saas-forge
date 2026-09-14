package io.saasforge.iam.application.authentication;

public final class RevocationIndexUnavailableException extends RuntimeException {
    public static final String CODE = "REVOCATION_INDEX_UNAVAILABLE";

    public RevocationIndexUnavailableException(Throwable cause) {
        super("撤销索引不可用", cause);
    }

    public RevocationIndexUnavailableException() {
        super("撤销索引尚未就绪");
    }
}
