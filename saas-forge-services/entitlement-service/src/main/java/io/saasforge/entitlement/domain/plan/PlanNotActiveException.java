package io.saasforge.entitlement.domain.plan;

public final class PlanNotActiveException extends RuntimeException {
    public static final String CODE = "PLAN_NOT_ACTIVE";

    public PlanNotActiveException() {
        super("只有 ACTIVE Plan 可以创建 Subscription");
    }
}
