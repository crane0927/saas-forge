package io.saasforge.iam.domain.shared;

import java.util.Arrays;

/**
 * 已计算的 SHA-256 摘要。领域对象只接收摘要，避免不透明凭据进入持久化层。
 */
public final class Sha256Digest {

    private static final int SHA_256_LENGTH = 32;

    private final byte[] value;

    private Sha256Digest(byte[] value) {
        this.value = value;
    }

    public static Sha256Digest of(byte[] value) {
        if (value == null || value.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("SHA-256 摘要必须为 32 字节");
        }
        return new Sha256Digest(value.clone());
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Sha256Digest digest && Arrays.equals(value, digest.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "[redacted]";
    }
}
