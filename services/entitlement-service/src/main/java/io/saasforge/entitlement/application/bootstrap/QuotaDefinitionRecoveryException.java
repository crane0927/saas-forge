package io.saasforge.entitlement.application.bootstrap;

public class QuotaDefinitionRecoveryException extends RuntimeException {
    private final String code;
    public QuotaDefinitionRecoveryException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
