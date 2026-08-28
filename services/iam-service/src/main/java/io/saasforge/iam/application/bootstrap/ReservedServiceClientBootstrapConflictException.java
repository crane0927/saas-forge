package io.saasforge.iam.application.bootstrap;

public final class ReservedServiceClientBootstrapConflictException extends RuntimeException {
    public enum Reason {
        CLIENT_REVOKED,
        MOUNTED_SECRET_NOT_CURRENT,
        CLIENT_CONFIGURATION_MISMATCH
    }

    private final Reason reason;

    public ReservedServiceClientBootstrapConflictException(ReservedServiceClient service) {
        this(service, Reason.CLIENT_CONFIGURATION_MISMATCH);
    }

    public ReservedServiceClientBootstrapConflictException(ReservedServiceClient service, Reason reason) {
        super(message(service, reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(ReservedServiceClient service, Reason reason) {
        return switch (reason) {
            case CLIENT_REVOKED -> "保留 OAuth Client 已吊销，必须执行 Replacement Job: " + service.displayName();
            case MOUNTED_SECRET_NOT_CURRENT -> "挂载的保留 OAuth Client Secret 已过期或吊销，必须更新外部 Secret: "
                    + service.displayName();
            case CLIENT_CONFIGURATION_MISMATCH -> "保留 OAuth Client 状态与引导输入不一致: "
                    + service.displayName();
        };
    }
}
