package io.saasforge.iambootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretTextFileReaderTest {
    @TempDir
    Path directory;

    private final SecretTextFileReader reader = new SecretTextFileReader();

    @Test
    void readsSingleLineUtf8AndRemovesOneTerminalLineEnding() throws Exception {
        Path secret = directory.resolve("secret");
        Files.writeString(secret, "Random-Initial-Password-2026\r\n");

        assertEquals("Random-Initial-Password-2026", reader.read(secret, 512));
    }

    @Test
    void terminalLineEndingDoesNotCountTowardContentByteLimit() throws Exception {
        Path secret = directory.resolve("request-id");
        String uuidV7 = "00000000-0000-7000-8000-000000000000";

        Files.writeString(secret, uuidV7 + "\n");
        assertEquals(uuidV7, reader.read(secret, 36));

        Files.writeString(secret, uuidV7 + "\r\n");
        assertEquals(uuidV7, reader.read(secret, 36));
    }

    @Test
    void rejectsContentOverByteLimitEvenWithTerminalLineEnding() throws Exception {
        Path secret = directory.resolve("oversized-secret");
        Files.writeString(secret, "12345\n");

        assertThrows(IllegalArgumentException.class, () -> reader.read(secret, 4));
    }

    @Test
    void rejectsMultilineSecretWithoutIncludingPathOrContentInMessage() throws Exception {
        Path secret = directory.resolve("sensitive-name");
        Files.writeString(secret, "first-line\nsecond-line");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> reader.read(secret, 512));

        assertFalse(exception.getMessage().contains(secret.toString()));
        assertFalse(exception.getMessage().contains("first-line"));
    }
}
