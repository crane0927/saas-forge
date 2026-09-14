package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class DurableRevocationRow {
    private UUID jti;
    private String kid;
    private OffsetDateTime expiresAt;
    private boolean jtiRevoked;
    private boolean kidRevoked;

    public UUID getJti() { return jti; }
    public void setJti(UUID jti) { this.jti = jti; }
    public String getKid() { return kid; }
    public void setKid(String kid) { this.kid = kid; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isJtiRevoked() { return jtiRevoked; }
    public void setJtiRevoked(boolean jtiRevoked) { this.jtiRevoked = jtiRevoked; }
    public boolean isKidRevoked() { return kidRevoked; }
    public void setKidRevoked(boolean kidRevoked) { this.kidRevoked = kidRevoked; }
}
