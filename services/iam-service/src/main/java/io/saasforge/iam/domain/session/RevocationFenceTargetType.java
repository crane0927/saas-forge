package io.saasforge.iam.domain.session;

public enum RevocationFenceTargetType {
    MEMBERSHIP("membership"),
    TENANT("tenant");

    private final String keySegment;

    RevocationFenceTargetType(String keySegment) {
        this.keySegment = keySegment;
    }

    public String keySegment() {
        return keySegment;
    }
}
