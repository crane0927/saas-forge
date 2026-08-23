CREATE TABLE tenant_administrator_initialization_workflows (
    workflow_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    administrator_email TEXT NOT NULL,
    administrator_display_name VARCHAR(200),
    identity_request_id UUID NOT NULL UNIQUE,
    consume_operation_id UUID NOT NULL UNIQUE,
    release_operation_id UUID NOT NULL UNIQUE,
    password_delivery_request_id UUID NOT NULL UNIQUE,
    trace_id CHAR(32),
    outcome_code TEXT,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT uq_tenant_admin_initialization_caller_key UNIQUE (actor_identity_id, idempotency_key),
    CONSTRAINT ck_tenant_admin_initialization_ids CHECK (
        uuid_extract_version(workflow_id) = 7
        AND uuid_extract_version(tenant_id) = 7
        AND uuid_extract_version(actor_identity_id) = 7
        AND uuid_extract_version(idempotency_key) = 7
        AND uuid_extract_version(identity_request_id) = 7
        AND uuid_extract_version(consume_operation_id) = 7
        AND uuid_extract_version(release_operation_id) = 7
        AND uuid_extract_version(password_delivery_request_id) = 7),
    CONSTRAINT ck_tenant_admin_initialization_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_admin_initialization_trace
        CHECK (trace_id IS NULL OR trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    CONSTRAINT ck_tenant_admin_initialization_completion CHECK (
        (outcome_code IS NULL AND response_status IS NULL AND response_body IS NULL
            AND completed_at IS NULL AND expires_at IS NULL)
        OR (outcome_code IS NOT NULL AND response_status IS NOT NULL
            AND completed_at IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT ck_tenant_admin_initialization_success CHECK (
        (outcome_code = 'SUCCESS') = (response_body IS NOT NULL))
);

CREATE UNIQUE INDEX uq_tenant_admin_initialization_active_tenant
    ON tenant_administrator_initialization_workflows (tenant_id)
    WHERE outcome_code IS NULL;

ALTER TABLE memberships
    ADD CONSTRAINT uq_memberships_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE tenant_roles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    role_key VARCHAR(64) NOT NULL,
    system_managed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_tenant_roles_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT uq_tenant_roles_key UNIQUE (tenant_id, role_key),
    CONSTRAINT uq_tenant_roles_tenant_id_id UNIQUE (tenant_id, id)
);

CREATE TABLE membership_role_assignments (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    membership_id UUID NOT NULL,
    role_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (membership_id, role_id),
    CONSTRAINT fk_membership_role_assignment_membership
        FOREIGN KEY (tenant_id, membership_id) REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_membership_role_assignment_role
        FOREIGN KEY (tenant_id, role_id) REFERENCES tenant_roles (tenant_id, id)
);

CREATE TABLE initial_tenant_administrators (
    tenant_id UUID PRIMARY KEY REFERENCES tenants (id),
    membership_id UUID NOT NULL UNIQUE,
    established_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_initial_tenant_administrator_membership
        FOREIGN KEY (tenant_id, membership_id) REFERENCES memberships (tenant_id, id)
);

CREATE TABLE password_setup_delivery_work_items (
    workflow_id UUID PRIMARY KEY REFERENCES tenant_administrator_initialization_workflows (workflow_id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    identity_id UUID NOT NULL,
    delivery_request_id UUID NOT NULL UNIQUE,
    work_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_password_setup_work_item_ids CHECK (
        uuid_extract_version(identity_id) = 7
        AND uuid_extract_version(delivery_request_id) = 7),
    CONSTRAINT ck_password_setup_work_item_status
        CHECK (work_status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_password_setup_work_item_completion
        CHECK ((work_status = 'COMPLETED') = (completed_at IS NOT NULL))
);

ALTER TABLE tenant_administrator_initialization_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_administrator_initialization_workflows FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE membership_role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_role_assignments FORCE ROW LEVEL SECURITY;
ALTER TABLE initial_tenant_administrators ENABLE ROW LEVEL SECURITY;
ALTER TABLE initial_tenant_administrators FORCE ROW LEVEL SECURITY;
ALTER TABLE password_setup_delivery_work_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_setup_delivery_work_items FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_admin_workflows_runtime_access ON tenant_administrator_initialization_workflows
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_admin_workflows_migration_access ON tenant_administrator_initialization_workflows
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

CREATE POLICY tenant_roles_runtime_access ON tenant_roles
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_roles_migration_access ON tenant_roles
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

CREATE POLICY membership_role_assignments_runtime_access ON membership_role_assignments
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY membership_role_assignments_migration_access ON membership_role_assignments
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

CREATE POLICY initial_tenant_administrators_runtime_access ON initial_tenant_administrators
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY initial_tenant_administrators_migration_access ON initial_tenant_administrators
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

CREATE POLICY password_setup_work_items_runtime_access ON password_setup_delivery_work_items
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY password_setup_work_items_migration_access ON password_setup_delivery_work_items
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_administrator_initialization_workflows TO tenant_access_app;
GRANT SELECT, INSERT ON tenant_roles TO tenant_access_app;
GRANT SELECT, INSERT ON membership_role_assignments TO tenant_access_app;
GRANT SELECT, INSERT ON initial_tenant_administrators TO tenant_access_app;
GRANT SELECT, INSERT, UPDATE ON password_setup_delivery_work_items TO tenant_access_app;
