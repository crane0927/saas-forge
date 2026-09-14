package io.saasforge.iam.application.authentication;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

public class PasswordSetupDeliveryService {
    private final PasswordSetupDeliveryTransaction transaction;
    private final PasswordSetupMailer mailer;
    private final URI setupPageUri;

    public PasswordSetupDeliveryService(
            PasswordSetupDeliveryTransaction transaction, PasswordSetupMailer mailer, URI setupPageUri) {
        this.transaction = transaction;
        this.mailer = mailer;
        this.setupPageUri = requireSetupPageUri(setupPageUri);
    }

    public PasswordSetupDeliveryResult deliver(
            UUID callerClientId, UUID requestId, UUID identityId, String traceId) {
        PasswordSetupDeliveryAttempt attempt = transaction.prepare(callerClientId, requestId, identityId);
        if (attempt.completed()) {
            return attempt.completedResult();
        }
        try {
            mailer.send(new PasswordSetupMail(
                    attempt.recipient(), setupLink(attempt.token())));
        } catch (PasswordSetupDeliveryUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PasswordSetupDeliveryUnavailableException(exception);
        }
        if (!transaction.confirm(
                callerClientId, requestId, identityId, attempt.challengeId(), attempt.challengeExpiresAt(), traceId)) {
            throw new PasswordSetupDeliveryUnavailableException();
        }
        return PasswordSetupDeliveryResult.DELIVERED;
    }

    private URI setupLink(String token) {
        try {
            return new URI(setupPageUri.getScheme(), setupPageUri.getAuthority(), setupPageUri.getPath(), null,
                    "token=" + token);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Password Setup 链接无法构造", exception);
        }
    }

    private static URI requireSetupPageUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !"/password-setup".equals(uri.getPath()) || uri.getQuery() != null
                || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Password Setup 页面必须是 HTTPS /password-setup 地址");
        }
        return uri;
    }
}
