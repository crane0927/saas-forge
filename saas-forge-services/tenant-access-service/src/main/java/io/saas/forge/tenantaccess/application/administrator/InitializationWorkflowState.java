package io.saas.forge.tenantaccess.application.administrator;

public enum InitializationWorkflowState {
    PREPARED,
    IDENTITY_READY,
    QUOTA_CONSUMED,
    ACTIVATING,
    COMPENSATING,
    SUCCEEDED,
    FAILED
}
