package io.saasforge.tenantaccess.application.administrator;

public class TenantAdministratorInitializationException extends RuntimeException {
    private final String code;

    public TenantAdministratorInitializationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
