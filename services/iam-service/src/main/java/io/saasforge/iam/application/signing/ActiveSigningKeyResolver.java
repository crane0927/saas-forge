package io.saasforge.iam.application.signing;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import java.time.Duration;
import java.util.List;

/** 解析唯一 ACTIVE Signing Key，并在配置不满足签发不变量时拒绝继续。 */
public final class ActiveSigningKeyResolver {

    private final SigningKeyRepository repository;

    public ActiveSigningKeyResolver(SigningKeyRepository repository) {
        this.repository = repository;
    }

    public SigningKey current() {
        List<SigningKey> activeKeys = repository.findActiveKeys();
        if (activeKeys.size() != 1) {
            throw new IllegalStateException("IAM 启动和签发时必须恰好存在一个 ACTIVE Signing Key，当前数量: "
                    + activeKeys.size());
        }
        return activeKeys.get(0);
    }

    public SigningKey currentForIssuance(Duration tokenTtl) {
        return repository.prepareActiveForIssuance(tokenTtl);
    }
}
