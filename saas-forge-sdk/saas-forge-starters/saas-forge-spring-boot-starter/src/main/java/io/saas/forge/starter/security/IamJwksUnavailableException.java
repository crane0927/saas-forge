package io.saas.forge.starter.security;

/** 公钥状态暂不可确认，区别于已确定的无效凭证。 */
final class IamJwksUnavailableException extends RuntimeException {
    IamJwksUnavailableException(Throwable cause) {
        super("IAM 公钥暂不可用", cause);
    }
}
