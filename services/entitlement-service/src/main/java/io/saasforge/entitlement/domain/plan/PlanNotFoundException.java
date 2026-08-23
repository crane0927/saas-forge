package io.saasforge.entitlement.domain.plan;

public final class PlanNotFoundException extends RuntimeException {
    public static final String CODE = "PLAN_NOT_FOUND";

    public PlanNotFoundException() {
        super("Plan 不存在");
    }
}
