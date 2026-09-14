package io.saasforge.audit.application;

public final class AuditIsolationReplayRejectedException extends RuntimeException {
    public AuditIsolationReplayRejectedException(String message) {
        super(message);
    }
}
