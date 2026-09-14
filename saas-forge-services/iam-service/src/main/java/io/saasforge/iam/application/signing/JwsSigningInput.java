package io.saasforge.iam.application.signing;

import java.util.Arrays;

/** 已完成 Base64url 编码并以点号连接的 JWS Signing Input。 */
public final class JwsSigningInput {

    private final byte[] value;

    private JwsSigningInput(byte[] value) {
        this.value = value;
    }

    public static JwsSigningInput of(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("JWS Signing Input 不能为空");
        }
        return new JwsSigningInput(Arrays.copyOf(value, value.length));
    }

    public byte[] bytes() {
        return Arrays.copyOf(value, value.length);
    }
}
