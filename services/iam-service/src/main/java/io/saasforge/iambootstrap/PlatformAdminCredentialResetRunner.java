package io.saasforge.iambootstrap;

import io.saasforge.iam.application.bootstrap.PlatformAdminCredentialResetResult;
import io.saasforge.iam.application.bootstrap.PlatformAdminCredentialResetService;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class PlatformAdminCredentialResetRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdminCredentialResetRunner.class);

    private final PlatformAdminCredentialResetService resetService;
    private final SecretTextFileReader secretReader;
    private final TraceIdGenerator traceIdGenerator;
    private final Path resetRequestIdFile;
    private final Path passwordFile;

    PlatformAdminCredentialResetRunner(
            PlatformAdminCredentialResetService resetService,
            SecretTextFileReader secretReader,
            TraceIdGenerator traceIdGenerator,
            Path resetRequestIdFile,
            Path passwordFile) {
        this.resetService = resetService;
        this.secretReader = secretReader;
        this.traceIdGenerator = traceIdGenerator;
        this.resetRequestIdFile = resetRequestIdFile;
        this.passwordFile = passwordFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID resetRequestId = parseUuidV7(secretReader.read(resetRequestIdFile, 36));
        String password = secretReader.read(passwordFile, 512);
        String traceId = traceIdGenerator.next();
        PlatformAdminCredentialResetResult result = resetService.reset(resetRequestId, password, traceId);
        LOGGER.info(
                "Platform Admin initial credential reset outcome={} resetRequestId={} identityId={} credentialId={} "
                        + "credentialExpiresAt={} traceId={}",
                result.outcome(), result.resetRequestId(), result.identityId(), result.credentialId(),
                result.credentialExpiresAt(), traceId);
    }

    private static UUID parseUuidV7(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.version() != 7 || !parsed.toString().equals(value)) {
                throw new IllegalArgumentException("resetRequestId 必须是规范小写 UUIDv7");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("resetRequestId 必须是规范小写 UUIDv7");
        }
    }
}
