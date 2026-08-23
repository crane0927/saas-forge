package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PasswordSetupDeliveryServiceTest {
    private static final UUID CLIENT_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5001");
    private static final UUID REQUEST_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5002");
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5003");
    private static final UUID CHALLENGE_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5004");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-23T06:00:00Z");
    private static final String TOKEN = "A".repeat(43);

    @Test
    void sendsOnlyTheAuthoritySelectedRecipientAndFragmentLink() {
        PasswordSetupDeliveryTransaction transaction = Mockito.mock(PasswordSetupDeliveryTransaction.class);
        PasswordSetupMailer mailer = Mockito.mock(PasswordSetupMailer.class);
        when(transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID))
                .thenReturn(PasswordSetupDeliveryAttempt.pending(
                        CHALLENGE_ID, "authority@example.test", TOKEN, EXPIRES_AT));
        when(transaction.confirm(CLIENT_ID, REQUEST_ID, IDENTITY_ID, CHALLENGE_ID, EXPIRES_AT, null))
                .thenReturn(true);
        PasswordSetupDeliveryService service = new PasswordSetupDeliveryService(
                transaction, mailer, URI.create("https://console.example.test/password-setup"));

        assertEquals(PasswordSetupDeliveryResult.DELIVERED,
                service.deliver(CLIENT_ID, REQUEST_ID, IDENTITY_ID, null));

        ArgumentCaptor<PasswordSetupMail> captured = ArgumentCaptor.forClass(PasswordSetupMail.class);
        verify(mailer).send(captured.capture());
        assertEquals("authority@example.test", captured.getValue().recipient());
        assertEquals("https://console.example.test/password-setup#token=" + TOKEN,
                captured.getValue().setupLink().toString());
        assertFalse(captured.getValue().toString().contains(TOKEN));
        assertFalse(captured.getValue().toString().contains("authority@example.test"));
    }

    @Test
    void stableCompletionDoesNotCreateAnotherMailOrChallenge() {
        PasswordSetupDeliveryTransaction transaction = Mockito.mock(PasswordSetupDeliveryTransaction.class);
        PasswordSetupMailer mailer = Mockito.mock(PasswordSetupMailer.class);
        when(transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID))
                .thenReturn(PasswordSetupDeliveryAttempt.completed(PasswordSetupDeliveryResult.PASSWORD_READY));
        PasswordSetupDeliveryService service = new PasswordSetupDeliveryService(
                transaction, mailer, URI.create("https://console.example.test/password-setup"));

        assertEquals(PasswordSetupDeliveryResult.PASSWORD_READY,
                service.deliver(CLIENT_ID, REQUEST_ID, IDENTITY_ID, null));

        verify(mailer, never()).send(any());
        verify(transaction, never()).confirm(any(), any(), any(), any(), any(), any());
    }

    @Test
    void smtpFailureAndObsoleteAttemptAreRetryableWithoutReturningSuccess() {
        PasswordSetupDeliveryTransaction transaction = Mockito.mock(PasswordSetupDeliveryTransaction.class);
        PasswordSetupMailer mailer = Mockito.mock(PasswordSetupMailer.class);
        when(transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID))
                .thenReturn(PasswordSetupDeliveryAttempt.pending(
                        CHALLENGE_ID, "authority@example.test", TOKEN, EXPIRES_AT));
        PasswordSetupDeliveryService service = new PasswordSetupDeliveryService(
                transaction, mailer, URI.create("https://console.example.test/password-setup"));

        Mockito.doThrow(new PasswordSetupDeliveryUnavailableException()).when(mailer).send(any());
        assertThrows(PasswordSetupDeliveryUnavailableException.class,
                () -> service.deliver(CLIENT_ID, REQUEST_ID, IDENTITY_ID, null));
        verify(transaction, never()).confirm(any(), any(), any(), any(), any(), any());

        Mockito.reset(mailer);
        when(transaction.confirm(CLIENT_ID, REQUEST_ID, IDENTITY_ID, CHALLENGE_ID, EXPIRES_AT, null))
                .thenReturn(false);
        assertThrows(PasswordSetupDeliveryUnavailableException.class,
                () -> service.deliver(CLIENT_ID, REQUEST_ID, IDENTITY_ID, null));
    }

    @Test
    void rejectsNonHttpsOrNonCanonicalSetupPage() {
        PasswordSetupDeliveryTransaction transaction = Mockito.mock(PasswordSetupDeliveryTransaction.class);
        PasswordSetupMailer mailer = Mockito.mock(PasswordSetupMailer.class);
        assertThrows(IllegalArgumentException.class, () -> new PasswordSetupDeliveryService(
                transaction, mailer, URI.create("http://console.example.test/password-setup")));
        assertThrows(IllegalArgumentException.class, () -> new PasswordSetupDeliveryService(
                transaction, mailer, URI.create("https://console.example.test/other")));
        assertTrue(TOKEN.matches("[A-Za-z0-9_-]{43}"));
    }
}
