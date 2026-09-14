package io.saasforge.iam.infrastructure.signing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.signing.JwsSigningInput;
import io.saasforge.iam.application.signing.JwtSigningAlgorithm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

class PemJcaJwtSigningAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPkcs8PemOnceAndProducesAValidRs256Signature() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        Path pemFile = temporaryDirectory.resolve("jwt-signing-key.pem");
        Files.writeString(pemFile, toPem(keyPair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        var adapter = new PemJcaJwtSigningAdapter("local/key/1", new FileSystemResource(pemFile));
        byte[] signingBytes = "eyJhbGciOiJSUzI1NiJ9.e30".getBytes(StandardCharsets.US_ASCII);

        byte[] signature = adapter.sign(
                "local/key/1", JwtSigningAlgorithm.RS256, JwsSigningInput.of(signingBytes));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signingBytes);
        assertTrue(verifier.verify(signature));
        assertThrows(IllegalStateException.class, () -> adapter.sign(
                "local/key/2", JwtSigningAlgorithm.RS256, JwsSigningInput.of(signingBytes)));
    }

    private static String toPem(byte[] privateKey) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey)
                + "\n-----END PRIVATE KEY-----\n";
    }
}
