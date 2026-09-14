package io.saasforge.entitlement.domain.plan;

public final class PlanAlreadyExistsException extends RuntimeException {
    public static final String CODE = "PLAN_ALREADY_EXISTS";

    public PlanAlreadyExistsException() {
        super("Plan code 已存在");
    }
}
