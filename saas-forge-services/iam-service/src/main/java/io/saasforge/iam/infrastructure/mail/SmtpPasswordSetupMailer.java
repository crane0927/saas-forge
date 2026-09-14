package io.saasforge.iam.infrastructure.mail;

import io.saasforge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import io.saasforge.iam.application.authentication.PasswordSetupMail;
import io.saasforge.iam.application.authentication.PasswordSetupMailer;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public final class SmtpPasswordSetupMailer implements PasswordSetupMailer {
    private final JavaMailSender sender;
    private final String from;

    public SmtpPasswordSetupMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(PasswordSetupMail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.recipient());
        message.setSubject("设置你的 SaaS Forge 密码");
        message.setText("请使用以下一次性安全链接设置密码（24 小时内有效）：\n\n"
                + mail.setupLink() + "\n\n如果你没有发起此操作，请忽略本邮件。");
        try {
            sender.send(message);
        } catch (MailException exception) {
            // 上游只观察可重试失败，不传播可能包含 SMTP 地址或认证细节的异常消息。
            throw new PasswordSetupDeliveryUnavailableException(exception);
        }
    }
}
