package io.saasforge.iam.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
}
