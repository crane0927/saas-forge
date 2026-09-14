package io.saas.forge.audit.infrastructure.messaging;

public final class AuditIsolationReplayPublishException extends RuntimeException {
    public AuditIsolationReplayPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
