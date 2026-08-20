package io.saasforge.iam.infrastructure.signing;

import io.saasforge.iam.application.signing.JwsSigningInput;
import io.saasforge.iam.application.signing.JwtSigningAlgorithm;
import io.saasforge.iam.application.signing.JwtSigningPort;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.Resource;

/** 开发环境从只读 PKCS#8 PEM 资源加载 RSA 私钥的 JCA 签名适配器。 */
public final class PemJcaJwtSigningAdapter implements JwtSigningPort {

    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END PRIVATE KEY-----";

    private final String configuredKeyVersionRef;
    private final PrivateKey privateKey;

    public PemJcaJwtSigningAdapter(String configuredKeyVersionRef, Resource privateKeyResource) {
        if (configuredKeyVersionRef == null || configuredKeyVersionRef.isBlank()) {
            throw new IllegalArgumentException("PEM Signing Key Version 引用不能为空");
        }
        this.configuredKeyVersionRef = configuredKeyVersionRef;
        this.privateKey = loadPrivateKey(privateKeyResource);
    }

    @Override
    public byte[] sign(String kmsKeyVersionRef, JwtSigningAlgorithm algorithm, JwsSigningInput signingInput) {
        if (!configuredKeyVersionRef.equals(kmsKeyVersionRef)) {
            throw new IllegalStateException("ACTIVE Signing Key 与已加载的 PEM Key Version 不匹配");
        }
        if (algorithm != JwtSigningAlgorithm.RS256) {
            throw new IllegalArgumentException("PEM JCA 适配器只支持 RS256");
        }
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.bytes());
            return signer.sign();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PEM JCA RS256 签名失败", ex);
        }
    }

    private static PrivateKey loadPrivateKey(Resource resource) {
        if (resource == null || !resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("PEM 私钥资源不存在或不可读");
        }
        try (InputStream input = resource.getInputStream()) {
            String pem = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            String encoded = pem.replace(PRIVATE_KEY_BEGIN, "")
                    .replace(PRIVATE_KEY_END, "")
                    .replaceAll("\\s", "");
            if (encoded.isEmpty() || !pem.contains(PRIVATE_KEY_BEGIN) || !pem.contains(PRIVATE_KEY_END)) {
                throw new IllegalStateException("PEM 私钥必须使用 PKCS#8 PRIVATE KEY 格式");
            }
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("无法加载 PEM RSA 私钥", ex);
        }
    }
}
