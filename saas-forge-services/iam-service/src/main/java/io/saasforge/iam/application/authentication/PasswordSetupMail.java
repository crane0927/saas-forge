package io.saasforge.iam.application.authentication;

import java.net.URI;

/** 仅在 SMTP 调用边界内存活的 Password Setup 邮件，禁止通过 toString 暴露收件人与链接。 */
public final class PasswordSetupMail {
    private final String recipient;
    private final URI setupLink;

    public PasswordSetupMail(String recipient, URI setupLink) {
        if (recipient == null || recipient.isBlank() || setupLink == null) {
            throw new IllegalArgumentException("Password Setup 邮件必要字段不合法");
        }
        this.recipient = recipient;
        this.setupLink = setupLink;
    }

    public String recipient() {
        return recipient;
    }

    public URI setupLink() {
        return setupLink;
    }

    @Override
    public String toString() {
        return "PasswordSetupMail[recipient=[redacted], setupLink=[redacted]]";
    }
}
