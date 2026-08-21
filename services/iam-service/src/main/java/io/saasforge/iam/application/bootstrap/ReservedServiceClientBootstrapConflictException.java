package io.saasforge.iam.application.bootstrap;

public final class ReservedServiceClientBootstrapConflictException extends RuntimeException {
    public ReservedServiceClientBootstrapConflictException(ReservedServiceClient service) {
        super("保留 OAuth Client 状态与引导输入不一致: " + service.displayName());
    }
}
