package io.saasforge.entitlement.application.quota;

import io.saasforge.entitlement.domain.quota.QuotaOperationOutcome;

public final class QuotaCommandException extends RuntimeException {
    private final QuotaOperationOutcome outcome;
    private final Integer usage;
    private final Integer limit;
    private final boolean replayed;

    public QuotaCommandException(
            QuotaOperationOutcome outcome, Integer usage, Integer limit, boolean replayed) {
        super(outcome.name());
        this.outcome = outcome;
        this.usage = usage;
        this.limit = limit;
        this.replayed = replayed;
    }

    public QuotaOperationOutcome outcome() { return outcome; }
    public Integer usage() { return usage; }
    public Integer limit() { return limit; }
    public boolean replayed() { return replayed; }
}
