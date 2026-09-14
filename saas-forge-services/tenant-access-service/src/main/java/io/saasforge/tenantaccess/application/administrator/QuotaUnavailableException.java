package io.saasforge.tenantaccess.application.administrator;

public final class QuotaUnavailableException extends RuntimeException {
    private final String code;

    public QuotaUnavailableException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
