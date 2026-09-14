package io.saasforge.sdk.auth;

public record ServiceJwtVerificationKey(String kid, String modulus, String exponent) {
    public ServiceJwtVerificationKey {
        if (kid == null || kid.isBlank() || modulus == null || modulus.isBlank()
                || exponent == null || exponent.isBlank()) {
            throw new IllegalArgumentException("Service JWT 验证公钥字段不能为空");
        }
    }
}
