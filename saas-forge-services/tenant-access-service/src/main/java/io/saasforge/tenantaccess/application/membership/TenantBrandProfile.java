package io.saasforge.tenantaccess.application.membership;

public record TenantBrandProfile(
        String displayName,
        String logoUrl,
        String faviconUrl,
        String primaryColor,
        String accentColor) {

    public TenantBrandProfile {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Brand Display Name 不能为空");
        }
        if (primaryColor == null || accentColor == null) {
            throw new IllegalArgumentException("Brand Color 不能为空");
        }
    }
}
