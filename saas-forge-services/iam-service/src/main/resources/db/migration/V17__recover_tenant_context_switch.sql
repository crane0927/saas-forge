ALTER TABLE iam_tenant_context_switches
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN lease_owner TEXT,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN recovery_exhausted_at TIMESTAMPTZ,
    ADD COLUMN last_failure TEXT;

UPDATE iam_tenant_context_switches
SET next_attempt_at = created_at;

ALTER TABLE iam_tenant_context_switches
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ADD CONSTRAINT ck_iam_tenant_context_switches_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_iam_tenant_context_switches_lease CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    ),
    ADD CONSTRAINT ck_iam_tenant_context_switches_failure_summary CHECK (
        last_failure IS NULL OR char_length(last_failure) <= 100
    );

DROP INDEX uq_iam_tenant_context_switches_one_blocking_family;

CREATE UNIQUE INDEX uq_iam_tenant_context_switches_one_blocking_family
    ON iam_tenant_context_switches (family_id)
    WHERE switch_status = 'AWAITING_REFRESH'
       OR (switch_status = 'PENDING' AND recovery_exhausted_at IS NULL);

CREATE INDEX ix_iam_tenant_context_switches_recovery
    ON iam_tenant_context_switches (next_attempt_at, created_at)
    WHERE switch_status = 'PENDING' AND recovery_exhausted_at IS NULL;
