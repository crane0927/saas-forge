package io.saasforge.entitlement.domain.plan;

public final class PlanTransitionException extends RuntimeException {
    public static final String CODE = "PLAN_TRANSITION_INVALID";

    public PlanTransitionException() {
        super("Plan 只能从 DRAFT 激活");
    }
}
