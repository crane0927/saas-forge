package io.saasforge.iam.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ClasspathCompromisedPasswordCheckerTest {
    @Test
    void loadsFixedSecListsAndRepositoryDigests() {
        var checker = new ClasspathCompromisedPasswordChecker("prod");

        assertTrue(checker.isCompromised("password"));
        assertTrue(checker.isCompromised("saasforge2026"));
        assertFalse(checker.isCompromised("Unique-\u4e0d\u53ef\u731c-2026!"));
    }

    @Test
    void productionFailsWhenArtifactIsMissingOrCorrupt() {
        assertThrows(IllegalStateException.class,
                () -> new ClasspathCompromisedPasswordChecker("prod", path -> null));
        byte[] metadata = "artifact.sha256=00\nartifact.count=1\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalStateException.class, () -> new ClasspathCompromisedPasswordChecker("production", path ->
                new ByteArrayInputStream(path.endsWith("properties") ? metadata : new byte[32])));
    }

    @Test
    void validatesMissingLengthDuplicateAndUnreadableArtifactsInEveryEnvironment() throws Exception {
        byte[] oneDigest = new byte[32];
        byte[] twoDuplicateDigests = new byte[64];

        assertFalse(new ClasspathCompromisedPasswordChecker("test", path ->
                path.endsWith("properties") ? new ByteArrayInputStream(metadata(oneDigest, 1)) : null)
                .isCompromised("password"));
        assertFalse(new ClasspathCompromisedPasswordChecker("test", path ->
                new ByteArrayInputStream(path.endsWith("properties") ? metadata(oneDigest, 2) : oneDigest))
                .isCompromised("password"));
        assertFalse(new ClasspathCompromisedPasswordChecker("test", path ->
                new ByteArrayInputStream(path.endsWith("properties")
                        ? metadata(twoDuplicateDigests, 2)
                        : twoDuplicateDigests))
                .isCompromised("password"));
        assertFalse(new ClasspathCompromisedPasswordChecker("development", path -> {
            throw new IOException("unreadable");
        }).isCompromised("password"));
    }

    private static byte[] metadata(byte[] artifact, int count) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
            return ("artifact.sha256=" + digest + "\nartifact.count=" + count + "\n")
                    .getBytes(StandardCharsets.US_ASCII);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
