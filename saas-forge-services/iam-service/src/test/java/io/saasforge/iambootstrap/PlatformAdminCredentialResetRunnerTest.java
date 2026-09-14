package io.saasforge.iambootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.application.bootstrap.PlatformAdminCredentialResetResult;
import io.saasforge.iam.application.bootstrap.PlatformAdminCredentialResetService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class PlatformAdminCredentialResetRunnerTest {

    @Test
    void readsOnlyMountedRequestIdAndPasswordSecrets() {
        PlatformAdminCredentialResetService service = mock(PlatformAdminCredentialResetService.class);
        SecretTextFileReader reader = mock(SecretTextFileReader.class);
        TraceIdGenerator traceIds = mock(TraceIdGenerator.class);
        Path requestFile = Path.of("reset-request-id-secret");
        Path passwordFile = Path.of("reset-password-secret");
        UUID requestId = uuidV7();
        String password = "New-Random-Initial-Password-2026";
        String traceId = "1234567890abcdef1234567890abcdef";
        when(reader.read(requestFile, 36)).thenReturn(requestId.toString());
        when(reader.read(passwordFile, 512)).thenReturn(password);
        when(traceIds.next()).thenReturn(traceId);
        when(service.reset(requestId, password, traceId)).thenReturn(new PlatformAdminCredentialResetResult(
                PlatformAdminCredentialResetResult.Outcome.RESET,
                requestId, uuidV7(), uuidV7(), Instant.parse("2026-08-22T00:00:00Z")));

        new PlatformAdminCredentialResetRunner(service, reader, traceIds, requestFile, passwordFile)
                .run(mock(ApplicationArguments.class));

        verify(reader).read(requestFile, 36);
        verify(reader).read(passwordFile, 512);
        verify(service).reset(requestId, password, traceId);
    }

    @Test
    void rejectsNonCanonicalOrNonV7RequestId() {
        PlatformAdminCredentialResetService service = mock(PlatformAdminCredentialResetService.class);
        SecretTextFileReader reader = mock(SecretTextFileReader.class);
        Path requestFile = Path.of("reset-request-id-secret");
        Path passwordFile = Path.of("reset-password-secret");
        when(reader.read(requestFile, 36)).thenReturn(UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class, () ->
                new PlatformAdminCredentialResetRunner(
                        service, reader, mock(TraceIdGenerator.class), requestFile, passwordFile)
                        .run(mock(ApplicationArguments.class)));
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0000000000007000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }
}
