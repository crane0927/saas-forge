package io.saasforge.iambootstrap;

import io.saasforge.iam.application.bootstrap.PlatformAdminBootstrapResult;
import io.saasforge.iam.application.bootstrap.PlatformAdminBootstrapService;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class PlatformAdminBootstrapRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdminBootstrapRunner.class);

    private final PlatformAdminBootstrapService bootstrapService;
    private final SecretTextFileReader secretReader;
    private final TraceIdGenerator traceIdGenerator;
    private final Path emailFile;
    private final Path passwordFile;

    PlatformAdminBootstrapRunner(
            PlatformAdminBootstrapService bootstrapService,
            SecretTextFileReader secretReader,
            TraceIdGenerator traceIdGenerator,
            Path emailFile,
            Path passwordFile) {
        this.bootstrapService = bootstrapService;
        this.secretReader = secretReader;
        this.traceIdGenerator = traceIdGenerator;
        this.emailFile = emailFile;
        this.passwordFile = passwordFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = secretReader.read(emailFile, 512);
        String password = secretReader.read(passwordFile, 512);
        String traceId = traceIdGenerator.next();
        PlatformAdminBootstrapResult result = bootstrapService.bootstrap(email, password, traceId);
        LOGGER.info(
                "Platform Admin bootstrap outcome={} identityId={} credentialId={} roleAssignmentId={} "
                        + "credentialExpiresAt={} traceId={}",
                result.outcome(), result.identityId(), result.credentialId(), result.roleAssignmentId(),
                result.credentialExpiresAt(), traceId);
    }
}
