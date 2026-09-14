package io.saasforge.iam.application.signing;

import io.saasforge.iam.domain.signing.SigningKey;
import java.time.Duration;

/** 使用当前唯一 ACTIVE Signing Key 完成 RS256 签名。 */
public final class JwtSigningService {

    private final ActiveSigningKeyResolver activeSigningKeyResolver;
    private final JwtSigningPort signingPort;

    public JwtSigningService(ActiveSigningKeyResolver activeSigningKeyResolver, JwtSigningPort signingPort) {
        this.activeSigningKeyResolver = activeSigningKeyResolver;
        this.signingPort = signingPort;
    }

    public JwtSignature sign(JwsSigningInput signingInput) {
        SigningKey activeKey = activeSigningKeyResolver.current();
        return sign(activeKey, signingInput);
    }

    /** 在选择 ACTIVE Key 后生成含同一 kid 的 Header，避免 Header 与实际签名密钥分离。 */
    public JwtSignature sign(JwsSigningInputFactory signingInputFactory) {
        SigningKey activeKey = activeSigningKeyResolver.current();
        return sign(activeKey, signingInputFactory.create(activeKey.kid()));
    }

    /** maxIssuedTokenTtl 必须在 KMS/HSM 签名前完成持久化。 */
    public JwtSignature sign(Duration tokenTtl, JwsSigningInputFactory signingInputFactory) {
        SigningKey activeKey = activeSigningKeyResolver.currentForIssuance(tokenTtl);
        return sign(activeKey, signingInputFactory.create(activeKey.kid()));
    }

    private JwtSignature sign(SigningKey activeKey, JwsSigningInput signingInput) {
        try {
            byte[] signature = signingPort.sign(
                    activeKey.keyVersionReference(), JwtSigningAlgorithm.RS256, signingInput);
            return new JwtSignature(activeKey.kid(), signature);
        } catch (RuntimeException ex) {
            // 密钥选择已经完成；失败时不得尝试其他 key，否则会绕过 ACTIVE 生命周期约束。
            throw new TokenSigningUnavailableException(ex);
        }
    }
}
