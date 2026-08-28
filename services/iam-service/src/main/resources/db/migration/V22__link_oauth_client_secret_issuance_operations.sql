UPDATE iam_oauth_client_management_operations operation
SET secret_record_id = secret.id
FROM iam_oauth_client_secrets secret
WHERE operation.operation_type IN ('CREATE', 'ROTATE')
  AND operation.outcome = 'SUCCEEDED'
  AND operation.secret_record_id IS NULL
  AND secret.client_id = operation.client_id
  AND secret.created_at = operation.completed_at;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM iam_oauth_client_management_operations
        WHERE operation_type IN ('CREATE', 'ROTATE')
          AND outcome = 'SUCCEEDED'
          AND secret_record_id IS NULL
    ) THEN
        RAISE EXCEPTION 'OAuth Client Secret 签发操作无法关联原 Secret 记录';
    END IF;
END $$;

ALTER TABLE iam_oauth_client_management_operations
    ADD CONSTRAINT ck_iam_oauth_client_operations_references CHECK (
        (outcome = 'REJECTED')
        OR (operation_type IN ('CREATE', 'ROTATE')
            AND original_operation_id IS NULL AND secret_record_id IS NOT NULL)
        OR (operation_type = 'RECOVER'
            AND original_operation_id IS NOT NULL AND secret_record_id IS NOT NULL)
        OR (operation_type = 'REVOKE'
            AND original_operation_id IS NULL AND secret_record_id IS NULL)
    );
