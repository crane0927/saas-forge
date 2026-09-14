CREATE TABLE audit_records (
    audit_record_id UUID PRIMARY KEY DEFAULT uuidv7(),
    source_event_id UUID NOT NULL,
    source TEXT NOT NULL,
    source_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(32),
    actor_identity_id UUID,
    tenant_id UUID,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id UUID NOT NULL,
    result TEXT NOT NULL,
    metadata JSONB NOT NULL,
    CONSTRAINT uq_audit_records_source_event UNIQUE (source, source_event_id),
    CONSTRAINT ck_audit_records_source_event_id_v7 CHECK (uuid_extract_version(source_event_id) = 7),
    CONSTRAINT ck_audit_records_trace_id CHECK (
        trace_id IS NULL OR (trace_id ~ '^[0-9a-f]{32}$' AND trace_id <> repeat('0', 32))
    ),
    CONSTRAINT ck_audit_records_actor_v7 CHECK (
        actor_identity_id IS NULL OR uuid_extract_version(actor_identity_id) = 7
    ),
    CONSTRAINT ck_audit_records_tenant_v7 CHECK (tenant_id IS NULL OR uuid_extract_version(tenant_id) = 7),
    CONSTRAINT ck_audit_records_resource_v7 CHECK (uuid_extract_version(resource_id) = 7),
    CONSTRAINT ck_audit_records_action CHECK (
        action IN ('SESSION_STARTED', 'TENANT_CREATED', 'TENANT_CONTEXT_SWITCHED')
    ),
    CONSTRAINT ck_audit_records_resource_type CHECK (resource_type IN ('REFRESH_TOKEN_FAMILY', 'TENANT')),
    CONSTRAINT ck_audit_records_result CHECK (result = 'SUCCESS'),
    CONSTRAINT ck_audit_records_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE TABLE audit_consumed_events (
    consumer_name TEXT NOT NULL,
    event_id UUID NOT NULL,
    source TEXT NOT NULL,
    source_type TEXT NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_audit_consumed_events_event_id_v7 CHECK (uuid_extract_version(event_id) = 7),
    CONSTRAINT ck_audit_consumed_events_consumer_name CHECK (char_length(btrim(consumer_name)) > 0)
);

REVOKE ALL ON audit_records FROM audit_app;
GRANT SELECT, INSERT ON audit_records TO audit_app;
REVOKE ALL ON audit_consumed_events FROM audit_app;
GRANT SELECT, INSERT ON audit_consumed_events TO audit_app;
