package io.saasforge.iam.application.authentication;

public final class AccessibleMembershipLimitExceededException extends RuntimeException {
    public static final String CODE = "ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED";

    public AccessibleMembershipLimitExceededException() {
        super("可访问的 Membership 数量超过登录上下文选择上限");
    }
}
