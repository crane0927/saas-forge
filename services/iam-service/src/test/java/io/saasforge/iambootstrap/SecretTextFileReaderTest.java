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
    void rejectsMultilineSecretWithoutIncludingPathOrContentInMessage() throws Exception {
        Path secret = directory.resolve("sensitive-name");
        Files.writeString(secret, "first-line\nsecond-line");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> reader.read(secret, 512));

        assertFalse(exception.getMessage().contains(secret.toString()));
        assertFalse(exception.getMessage().contains("first-line"));
    }
}
