package io.saasforge.entitlement.domain.plan;

public final class PlanInvalidException extends RuntimeException {
    public static final String CODE = "PLAN_INVALID";

    public PlanInvalidException(String message) {
        super(message);
    }
}
