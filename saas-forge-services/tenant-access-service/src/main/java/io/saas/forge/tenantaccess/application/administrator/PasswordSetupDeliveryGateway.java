package io.saas.forge.tenantaccess.application.administrator;

import java.util.UUID;

public interface PasswordSetupDeliveryGateway {
    enum NotificationState { NOT_REQUESTED, PENDING, MAIL_SERVICE_ACCEPTED, PASSWORD_READY, RECOVERY_REQUIRED }

    /** IAM 权威读取，不能由 deliver 的无返回值成功推断邮件状态。 */
    default NotificationState notification(UUID requestId, UUID identityId) {
        throw new UnsupportedOperationException("Notification query is not configured");
    }

    void deliver(UUID requestId, UUID identityId);
}
