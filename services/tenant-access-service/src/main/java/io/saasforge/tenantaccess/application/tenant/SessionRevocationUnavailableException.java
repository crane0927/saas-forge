package io.saasforge.tenantaccess.application.tenant;

/** 表示调用结果不确定；请求可能已在 IAM 建立 Fence，调用方必须按 fail closed 恢复。 */
public final class SessionRevocationUnavailableException extends RuntimeException {
    public SessionRevocationUnavailableException(Throwable cause) {
        super("IAM User Session Revocation 暂时不可用", cause);
    }
}
