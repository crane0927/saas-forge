package io.saasforge.iambootstrap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.application.bootstrap.PlatformAdminBootstrapResult;
import io.saasforge.iam.application.bootstrap.PlatformAdminBootstrapService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class PlatformAdminBootstrapRunnerTest {

    @Test
    void passesMountedSecretsAndGeneratedTraceIdToTheBootstrapService() {
        PlatformAdminBootstrapService service = mock(PlatformAdminBootstrapService.class);
        SecretTextFileReader secretReader = mock(SecretTextFileReader.class);
        TraceIdGenerator traceIdGenerator = mock(TraceIdGenerator.class);
        Path emailFile = Path.of("email-secret");
        Path passwordFile = Path.of("password-secret");
        String email = "platform-admin@example.test";
        String password = "Random-Initial-Password-2026";
        String traceId = "1234567890abcdef1234567890abcdef";
        PlatformAdminBootstrapResult result = new PlatformAdminBootstrapResult(
                PlatformAdminBootstrapResult.Outcome.INITIALIZED,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-08-22T00:00:00Z"));
        when(secretReader.read(emailFile, 512)).thenReturn(email);
        when(secretReader.read(passwordFile, 512)).thenReturn(password);
        when(traceIdGenerator.next()).thenReturn(traceId);
        when(service.bootstrap(email, password, traceId)).thenReturn(result);

        new PlatformAdminBootstrapRunner(
                        service, secretReader, traceIdGenerator, emailFile, passwordFile)
                .run(mock(ApplicationArguments.class));

        verify(service).bootstrap(email, password, traceId);
    }
}
