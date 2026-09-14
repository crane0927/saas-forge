package io.saasforge.auditreplay;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.saasforge.audit.infrastructure.messaging.AuditIsolationReplayPublisher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class AuditIsolationReplayRunnerTest {
    private final AuditIsolationReplayPublisher publisher = mock(AuditIsolationReplayPublisher.class);
    private final ApplicationArguments arguments = mock(ApplicationArguments.class);

    @Test
    void acceptsOnlyCanonicalUuidV7IsolationId() {
        String isolationId = "019535d9-0001-7000-8000-000000000085";

        new AuditIsolationReplayRunner(publisher, isolationId).run(arguments);

        verify(publisher).replay(UUID.fromString(isolationId));
    }

    @Test
    void rejectsPayloadOrNonUuidV7Input() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditIsolationReplayRunner(publisher, "{\"payload\":\"forged\"}")
                        .run(arguments));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditIsolationReplayRunner(
                        publisher, "123e4567-e89b-12d3-a456-426614174000")
                        .run(arguments));
    }
}
