package io.saasforge.audit.infrastructure.messaging;

public final class AuditIsolationReplayPublishException extends RuntimeException {
    public AuditIsolationReplayPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
