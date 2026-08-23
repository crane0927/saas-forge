package io.saasforge.entitlement.application.quota;

public record QuotaCommandResult(int usage, int limit, boolean replayed) {
}
