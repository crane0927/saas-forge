package io.saasforge.tenantaccess.application.administrator;

public final class RemoteWorkflowUnavailableException extends RuntimeException {
    public RemoteWorkflowUnavailableException(Throwable cause) {
        super("Tenant Admin 初始化依赖暂时不可用", cause);
    }
}
