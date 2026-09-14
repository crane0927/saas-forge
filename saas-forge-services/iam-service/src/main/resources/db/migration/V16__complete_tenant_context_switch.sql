ALTER TABLE iam_tenant_context_switches
    DROP CONSTRAINT ck_iam_tenant_context_switches_status,
    DROP CONSTRAINT ck_iam_tenant_context_switches_completion,
    ADD COLUMN result_http_status SMALLINT,
    ADD COLUMN refreshed_at TIMESTAMPTZ;

UPDATE iam_tenant_context_switches
SET result_http_status = 204
WHERE switch_status = 'NO_OP';

ALTER TABLE iam_tenant_context_switches
    ADD CONSTRAINT ck_iam_tenant_context_switches_status CHECK (
        switch_status IN (
            'PENDING', 'NO_OP', 'CURRENT_REJECTED', 'TARGET_REJECTED',
            'AWAITING_REFRESH', 'POST_SWITCH_REFRESHED', 'POST_SWITCH_REFRESH_REJECTED'
        )
    ),
    ADD CONSTRAINT ck_iam_tenant_context_switches_result CHECK (
        (switch_status = 'PENDING' AND completed_at IS NULL AND result_http_status IS NULL)
        OR (switch_status = 'NO_OP' AND completed_at IS NOT NULL AND result_http_status = 204)
        OR (switch_status IN ('CURRENT_REJECTED', 'TARGET_REJECTED')
            AND completed_at IS NOT NULL AND result_http_status IS NULL)
        OR (switch_status IN ('AWAITING_REFRESH', 'POST_SWITCH_REFRESHED', 'POST_SWITCH_REFRESH_REJECTED')
            AND completed_at IS NOT NULL AND result_http_status = 204)
    ),
    ADD CONSTRAINT ck_iam_tenant_context_switches_refreshed CHECK (
        (switch_status IN ('POST_SWITCH_REFRESHED', 'POST_SWITCH_REFRESH_REJECTED')
            AND refreshed_at IS NOT NULL AND refreshed_at >= completed_at)
        OR (switch_status NOT IN ('POST_SWITCH_REFRESHED', 'POST_SWITCH_REFRESH_REJECTED')
            AND refreshed_at IS NULL)
    );

DROP INDEX uq_iam_tenant_context_switches_one_pending_family;

CREATE UNIQUE INDEX uq_iam_tenant_context_switches_one_blocking_family
    ON iam_tenant_context_switches (family_id)
    WHERE switch_status IN ('PENDING', 'AWAITING_REFRESH');
