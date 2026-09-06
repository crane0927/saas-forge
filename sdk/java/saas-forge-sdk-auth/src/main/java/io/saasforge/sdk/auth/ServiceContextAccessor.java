package io.saasforge.sdk.auth;

import java.util.Optional;

/** 只读访问当前执行边界内已经验证的 Service Context。 */
@FunctionalInterface
public interface ServiceContextAccessor {

    Optional<ServiceContext> current();
}
