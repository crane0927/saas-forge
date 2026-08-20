package io.saasforge.iam.infrastructure.security;

import io.saasforge.iam.application.authentication.CompromisedPasswordChecker;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClasspathCompromisedPasswordChecker implements CompromisedPasswordChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClasspathCompromisedPasswordChecker.class);
    private static final String METADATA = "/password-blocklist/password-blocklist.properties";
    private static final String ARTIFACT = "/password-blocklist/password-blocklist.sha256";

    private final Set<Sha256Digest> digests;

    public ClasspathCompromisedPasswordChecker(String environment) {
        this(environment, path -> ClasspathCompromisedPasswordChecker.class.getResourceAsStream(path));
    }

    ClasspathCompromisedPasswordChecker(String environment, ResourceProvider resources) {
        this.digests = load(environment, resources);
    }

    @Override
    public boolean isCompromised(String normalizedPassword) {
        return digests.contains(Sha256Digest.of(sha256(normalizedPassword.getBytes(StandardCharsets.UTF_8))));
    }

    private Set<Sha256Digest> load(String environment, ResourceProvider resources) {
        try (InputStream metadata = resources.open(METADATA);
                InputStream artifact = resources.open(ARTIFACT)) {
            if (metadata == null || artifact == null) {
                return unavailable(environment, "弱口令摘要产物缺失", null);
            }
            Properties properties = new Properties();
            properties.load(metadata);
            byte[] content = artifact.readAllBytes();
            String actualSha256 = HexFormat.of().formatHex(sha256(content));
            String expectedSha256 = properties.getProperty("artifact.sha256");
            int expectedCount = Integer.parseInt(properties.getProperty("artifact.count"));
            if (!actualSha256.equals(expectedSha256) || content.length != expectedCount * 32) {
                return unavailable(environment, "弱口令摘要产物完整性校验失败", null);
            }
            Set<Sha256Digest> loaded = new HashSet<>(expectedCount * 4 / 3 + 1);
            for (int offset = 0; offset < content.length; offset += 32) {
                loaded.add(Sha256Digest.of(java.util.Arrays.copyOfRange(content, offset, offset + 32)));
            }
            if (loaded.size() != expectedCount) {
                return unavailable(environment, "弱口令摘要产物包含重复项", null);
            }
            return Set.copyOf(loaded);
        } catch (IOException | RuntimeException exception) {
            return unavailable(environment, "弱口令摘要产物无法读取", exception);
        }
    }

    private Set<Sha256Digest> unavailable(String environment, String message, Exception cause) {
        if ("prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(message, cause);
        }
        LOGGER.warn("{}；当前非生产环境将使用空弱口令表", message, cause);
        return Set.of();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    @FunctionalInterface
    interface ResourceProvider {
        InputStream open(String path) throws IOException;
    }
}
