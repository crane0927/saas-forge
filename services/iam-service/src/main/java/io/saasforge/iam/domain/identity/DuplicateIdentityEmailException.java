package io.saasforge.iam.domain.identity;

/** Identity 创建时规范化邮箱已被占用。 */
public final class DuplicateIdentityEmailException extends IllegalStateException {

    public DuplicateIdentityEmailException() {
        super("Identity 的规范化邮箱已存在");
    }
}
