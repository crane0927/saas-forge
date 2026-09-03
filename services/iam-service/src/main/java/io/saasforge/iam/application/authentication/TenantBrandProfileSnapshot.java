package io.saasforge.iam.application.authentication;

public record TenantBrandProfileSnapshot(
        String displayName,
        String logoUrl,
        String faviconUrl,
        String primaryColor,
        String accentColor) {}
