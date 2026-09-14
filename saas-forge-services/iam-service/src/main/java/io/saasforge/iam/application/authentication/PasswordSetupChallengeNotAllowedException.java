package io.saasforge.iam.application.authentication;

/** 内部签发被拒绝；匿名兑换端不得将该状态映射为可枚举的外部错误。 */
public final class PasswordSetupChallengeNotAllowedException extends RuntimeException {
    public PasswordSetupChallengeNotAllowedException() {
        super("Identity 不允许签发 Password Setup Challenge");
    }
}
