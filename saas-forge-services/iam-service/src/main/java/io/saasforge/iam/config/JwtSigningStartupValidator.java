package io.saasforge.iam.config;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** 在服务接受流量前验证数据库中恰好存在一个 ACTIVE Signing Key。 */
public final class JwtSigningStartupValidator implements SmartInitializingSingleton {

    private final ActiveSigningKeyResolver resolver;

    JwtSigningStartupValidator(ActiveSigningKeyResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void afterSingletonsInstantiated() {
        resolver.current();
    }
}
