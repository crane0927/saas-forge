package io.saasforge.sdk.auth;

import java.util.Optional;

/** 只读访问当前执行边界内已经验证的 Identity Context。 */
@FunctionalInterface
public interface IdentityContextAccessor {

    Optional<IdentityContext> current();
}
