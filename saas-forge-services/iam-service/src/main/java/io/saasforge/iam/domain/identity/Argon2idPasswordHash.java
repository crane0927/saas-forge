package io.saasforge.iam.domain.identity;

import java.util.Objects;

/** 已编码的固定参数 Argon2id 哈希；绝不持有原始密码。 */
public final class Argon2idPasswordHash {

    private static final String REQUIRED_PREFIX = "$argon2id$v=19$m=19456,t=2,p=1$";

    private final String encoded;

    private Argon2idPasswordHash(String encoded) {
        this.encoded = encoded;
    }

    public static Argon2idPasswordHash of(String encoded) {
        String value = Objects.requireNonNull(encoded, "密码哈希不能为空");
        if (!value.startsWith(REQUIRED_PREFIX) || value.length() <= REQUIRED_PREFIX.length()) {
            throw new IllegalArgumentException("密码哈希必须使用规定参数的 Argon2id");
        }
        return new Argon2idPasswordHash(value);
    }

    public String encoded() {
        return encoded;
    }

    @Override
    public String toString() {
        return "[redacted]";
    }
}
