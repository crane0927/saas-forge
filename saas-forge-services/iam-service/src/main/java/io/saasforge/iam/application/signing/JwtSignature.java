package io.saasforge.iam.application.signing;

import java.util.Arrays;

/** 签名结果及验证方选择公钥所需的 kid。 */
public final class JwtSignature {

    private final String kid;
    private final byte[] value;

    public JwtSignature(String kid, byte[] value) {
        if (kid == null || kid.isBlank() || value == null || value.length == 0) {
            throw new IllegalArgumentException("JWT 签名结果不完整");
        }
        this.kid = kid;
        this.value = Arrays.copyOf(value, value.length);
    }

    public String kid() {
        return kid;
    }

    public byte[] bytes() {
        return Arrays.copyOf(value, value.length);
    }
}
