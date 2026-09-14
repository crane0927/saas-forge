package io.saasforge.auditreplay;

import io.saasforge.audit.infrastructure.messaging.AuditIsolationReplayPublisher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class AuditIsolationReplayRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditIsolationReplayRunner.class);

    private final AuditIsolationReplayPublisher publisher;
    private final String isolationId;

    AuditIsolationReplayRunner(AuditIsolationReplayPublisher publisher, String isolationId) {
        this.publisher = publisher;
        this.isolationId = isolationId;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID parsedIsolationId = uuidV7(isolationId);
        var outcome = publisher.replay(parsedIsolationId);
        LOGGER.info("Audit Isolation replay isolationId={} outcome={}", parsedIsolationId, outcome);
    }

    private static UUID uuidV7(String raw) {
        UUID value;
        try {
            value = UUID.fromString(raw);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Audit Isolation ID 必须是规范 UUIDv7", exception);
        }
        if (value.version() != 7 || !value.toString().equals(raw)) {
            throw new IllegalArgumentException("Audit Isolation ID 必须是规范 UUIDv7");
        }
        return value;
    }
}
