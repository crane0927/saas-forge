package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class SigningKeyRow {
    private UUID id;
    private String kid;
    private String keyVersionReference;
    private String publicJwkModulus;
    private String publicJwkExponent;
    private String keyStatus;
    private OffsetDateTime publishedAt;
    private OffsetDateTime activatedAt;
    private OffsetDateTime retireAfter;
    private OffsetDateTime retiredAt;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getKid() { return kid; }
    public void setKid(String kid) { this.kid = kid; }
    public String getKeyVersionReference() { return keyVersionReference; }
    public void setKeyVersionReference(String keyVersionReference) { this.keyVersionReference = keyVersionReference; }
    public String getPublicJwkModulus() { return publicJwkModulus; }
    public void setPublicJwkModulus(String publicJwkModulus) { this.publicJwkModulus = publicJwkModulus; }
    public String getPublicJwkExponent() { return publicJwkExponent; }
    public void setPublicJwkExponent(String publicJwkExponent) { this.publicJwkExponent = publicJwkExponent; }
    public String getKeyStatus() { return keyStatus; }
    public void setKeyStatus(String keyStatus) { this.keyStatus = keyStatus; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
    public OffsetDateTime getRetireAfter() { return retireAfter; }
    public void setRetireAfter(OffsetDateTime retireAfter) { this.retireAfter = retireAfter; }
    public OffsetDateTime getRetiredAt() { return retiredAt; }
    public void setRetiredAt(OffsetDateTime retiredAt) { this.retiredAt = retiredAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
