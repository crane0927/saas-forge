package io.saasforge.iam.application.bootstrap;

public final class ReservedServiceClientReplacementException extends RuntimeException {
    public enum Reason {
        REQUEST_CONFLICT,
        OLD_CLIENT_NOT_FOUND,
        OLD_CLIENT_NOT_REVOKED,
        SERVICE_MISMATCH,
        NEW_CLIENT_ID_USED,
        ACTIVE_CLIENT_EXISTS
    }

    private final Reason reason;

    public ReservedServiceClientReplacementException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case REQUEST_CONFLICT -> "Replacement Request ID 已绑定不同输入，必须转人工处理";
            case OLD_CLIENT_NOT_FOUND -> "待替换的 Reserved OAuth Client 不存在";
            case OLD_CLIENT_NOT_REVOKED -> "只有已吊销的 Reserved OAuth Client 可以替换";
            case SERVICE_MISMATCH -> "待替换 Client 与服务键不匹配";
            case NEW_CLIENT_ID_USED -> "新 Client ID 已被使用";
            case ACTIVE_CLIENT_EXISTS -> "该服务已存在 ACTIVE Reserved OAuth Client";
        };
    }
}
