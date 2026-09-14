package io.saasforge.iam.application.authentication;

/** SMTP 等外部投递实现必须在明确接受邮件后才正常返回。 */
public interface PasswordSetupMailer {
    void send(PasswordSetupMail mail);
}
