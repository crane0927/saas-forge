package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.util.UUID;

public class AccessibleMembershipRow {

    private UUID membershipId;
    private UUID tenantId;
    private String tenantDisplayName;
    private String brandDisplayName;
    private String brandLogoUrl;
    private String brandFaviconUrl;
    private String brandPrimaryColor;
    private String brandAccentColor;

    public UUID getMembershipId() {
        return membershipId;
    }

    public void setMembershipId(UUID membershipId) {
        this.membershipId = membershipId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantDisplayName() {
        return tenantDisplayName;
    }

    public void setTenantDisplayName(String tenantDisplayName) {
        this.tenantDisplayName = tenantDisplayName;
    }

    public String getBrandDisplayName() {
        return brandDisplayName;
    }

    public void setBrandDisplayName(String value) {
        brandDisplayName = value;
    }

    public String getBrandLogoUrl() {
        return brandLogoUrl;
    }

    public void setBrandLogoUrl(String value) {
        brandLogoUrl = value;
    }

    public String getBrandFaviconUrl() {
        return brandFaviconUrl;
    }

    public void setBrandFaviconUrl(String value) {
        brandFaviconUrl = value;
    }

    public String getBrandPrimaryColor() {
        return brandPrimaryColor;
    }

    public void setBrandPrimaryColor(String value) {
        brandPrimaryColor = value;
    }

    public String getBrandAccentColor() {
        return brandAccentColor;
    }

    public void setBrandAccentColor(String value) {
        brandAccentColor = value;
    }
}
