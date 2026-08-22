package io.saasforge.iam.domain.identity;

/** Tenant 管理员初始化在 IAM 当前凭证事实下可采取的后续动作。 */
public enum IdentityCredentialStatus {
    SETUP_ALLOWED,
    PASSWORD_READY,
    RECOVERY_REQUIRED
}
