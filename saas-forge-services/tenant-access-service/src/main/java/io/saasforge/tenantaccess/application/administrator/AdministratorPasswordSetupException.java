package io.saasforge.tenantaccess.application.administrator;

public final class AdministratorPasswordSetupException extends RuntimeException {
    private final String code;
    private final long retryAfterSeconds;

    public AdministratorPasswordSetupException(String code, String message) {
        this(code, message, 0);
    }

    public AdministratorPasswordSetupException(String code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
