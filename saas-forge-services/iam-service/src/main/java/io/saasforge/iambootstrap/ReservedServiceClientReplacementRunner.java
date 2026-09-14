package io.saasforge.iambootstrap;

import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementInput;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementResult;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementService;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class ReservedServiceClientReplacementRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservedServiceClientReplacementRunner.class);

    private final ReservedServiceClientReplacementService service;
    private final SecretTextFileReader reader;
    private final TraceIdGenerator traceIds;
    private final String requestId;
    private final String serviceKey;
    private final String oldClientId;
    private final String newClientId;
    private final Path newSecretFile;

    ReservedServiceClientReplacementRunner(
            ReservedServiceClientReplacementService service,
            SecretTextFileReader reader,
            TraceIdGenerator traceIds,
            String requestId,
            String serviceKey,
            String oldClientId,
            String newClientId,
            Path newSecretFile) {
        this.service = service;
        this.reader = reader;
        this.traceIds = traceIds;
        this.requestId = requestId;
        this.serviceKey = serviceKey;
        this.oldClientId = oldClientId;
        this.newClientId = newClientId;
        this.newSecretFile = newSecretFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID parsedRequestId = uuidV7(requestId, "Replacement Request ID");
        ReservedServiceClient serviceClient;
        try {
            serviceClient = ReservedServiceClient.valueOf(serviceKey);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Reserved Service Key 必须是 IAM、TENANT_ACCESS 或 ENTITLEMENT", exception);
        }
        ReservedServiceClientReplacementResult result = service.replace(
                new ReservedServiceClientReplacementInput(
                        parsedRequestId,
                        serviceClient,
                        uuidV7(oldClientId, "旧 Client ID"),
                        uuidV7(newClientId, "新 Client ID"),
                        reader.read(newSecretFile, 43)),
                traceIds.next());
        LOGGER.info(
                "Reserved service OAuth Client replacement service={} clientId={} outcome={}",
                serviceClient.displayName(), result.clientId(), result.outcome());
    }

    private static UUID uuidV7(String raw, String field) {
        UUID value;
        try {
            value = UUID.fromString(raw);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " 必须是规范 UUIDv7", exception);
        }
        if (value.version() != 7 || !value.toString().equals(raw)) {
            throw new IllegalArgumentException(field + " 必须是规范 UUIDv7");
        }
        return value;
    }
}
