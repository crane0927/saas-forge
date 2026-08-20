package io.saasforge.iam.domain.client;

import java.util.Arrays;

public enum OAuthScope {
    RUNTIME_READ("runtime:read"),
    RUNTIME_QUOTA_WRITE("runtime:quota:write");

    private final String value;

    OAuthScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OAuthScope fromValue(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不允许的 OAuth Scope: " + value));
    }
}
