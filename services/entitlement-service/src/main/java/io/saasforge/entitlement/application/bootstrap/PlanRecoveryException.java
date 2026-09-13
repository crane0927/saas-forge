package io.saasforge.entitlement.application.bootstrap;

public class PlanRecoveryException extends RuntimeException {
    private final String code;
    public PlanRecoveryException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
