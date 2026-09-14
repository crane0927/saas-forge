package io.saasforge.iambootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementInput;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementResult;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

class ReservedServiceClientReplacementRunnerTest {
    private static final String REQUEST_ID = "0198f300-0000-7000-8000-000000000011";
    private static final String OLD_CLIENT_ID = "0198f300-0000-7000-8000-000000000012";
    private static final String NEW_CLIENT_ID = "0198f300-0000-7000-8000-000000000013";

    @Test
    void passesOnlyFixedDeploymentInputsAndSecretFile() {
        ReservedServiceClientReplacementService service = mock(ReservedServiceClientReplacementService.class);
        SecretTextFileReader reader = mock(SecretTextFileReader.class);
        TraceIdGenerator traceIds = mock(TraceIdGenerator.class);
        Path secretFile = Path.of("replacement-secret");
        when(reader.read(secretFile, 43)).thenReturn("mounted-secret");
        when(traceIds.next()).thenReturn("00000000000000000000000000000001");
        when(service.replace(any(), any())).thenReturn(new ReservedServiceClientReplacementResult(
                UUID.fromString(NEW_CLIENT_ID), ReservedServiceClientReplacementResult.Outcome.REPLACED));

        new ReservedServiceClientReplacementRunner(
                service, reader, traceIds, REQUEST_ID, "IAM", OLD_CLIENT_ID, NEW_CLIENT_ID, secretFile)
                .run(mock(ApplicationArguments.class));

        ArgumentCaptor<ReservedServiceClientReplacementInput> input =
                ArgumentCaptor.forClass(ReservedServiceClientReplacementInput.class);
        verify(service).replace(input.capture(), any());
        assertEquals(ReservedServiceClient.IAM, input.getValue().service());
        assertEquals(UUID.fromString(REQUEST_ID), input.getValue().replacementRequestId());
        assertEquals(UUID.fromString(OLD_CLIENT_ID), input.getValue().oldClientId());
        assertEquals(UUID.fromString(NEW_CLIENT_ID), input.getValue().newClientId());
        assertEquals("mounted-secret", input.getValue().newClientSecret());
        verify(reader).read(secretFile, 43);
    }

    @Test
    void rejectsUnknownServiceKeyBeforeReadingSecret() {
        ReservedServiceClientReplacementRunner runner = new ReservedServiceClientReplacementRunner(
                mock(ReservedServiceClientReplacementService.class),
                mock(SecretTextFileReader.class),
                mock(TraceIdGenerator.class),
                REQUEST_ID, "RUNTIME", OLD_CLIENT_ID, NEW_CLIENT_ID, Path.of("secret"));

        assertThrows(IllegalArgumentException.class, () -> runner.run(mock(ApplicationArguments.class)));
    }
}
