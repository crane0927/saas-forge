package io.saas.forge.entitlement.application.quota;

public record QuotaCommandResult(int usage, int limit, boolean replayed) {
}
