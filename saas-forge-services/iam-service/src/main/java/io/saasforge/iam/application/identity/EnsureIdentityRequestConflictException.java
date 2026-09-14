package io.saasforge.iam.application.identity;

/** 同一调用方 requestId 被用于不同的规范化请求。 */
public final class EnsureIdentityRequestConflictException extends RuntimeException {
    public EnsureIdentityRequestConflictException() {
        super("Identity 确保请求指纹冲突");
    }
}
