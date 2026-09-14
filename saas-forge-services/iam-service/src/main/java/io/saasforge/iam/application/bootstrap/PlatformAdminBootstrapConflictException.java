package io.saasforge.iam.application.bootstrap;

public final class PlatformAdminBootstrapConflictException extends RuntimeException {
    public static final String CODE = "PLATFORM_ADMIN_BOOTSTRAP_STATE_MISMATCH";

    public PlatformAdminBootstrapConflictException() {
        super("既有 Platform Admin 引导状态与输入不一致，未执行任何覆盖");
    }
}
