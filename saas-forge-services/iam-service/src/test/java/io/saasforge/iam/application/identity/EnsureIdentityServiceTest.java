package io.saasforge.iam.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityCredentialStatus;
import io.saasforge.iam.domain.identity.IdentityProvisioningFact;
import io.saasforge.iam.domain.identity.IdentityProvisioningRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnsureIdentityServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T05:00:00Z");
    private static final UUID CALLER_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID REQUEST_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
    private static final String HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

    private FakeIdentityProvisioningRepository requests;
    private FakeIdentityRepository identities;
    private EnsureIdentityService service;

    @BeforeEach
    void setUp() {
        requests = new FakeIdentityProvisioningRepository();
        identities = new FakeIdentityRepository();
        service = new EnsureIdentityService(
                requests, identities, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsSetupAllowedOnlyWhenIdentityHasNeverHadCredential() {
        identities.credentials = List.of();

        EnsureIdentityResult result = service.ensure(
                CALLER_ID, REQUEST_ID, " Admin@Example.Test ", "Tenant Admin");

        assertEquals(IDENTITY_ID, result.identityId());
        assertEquals(IdentityCredentialStatus.SETUP_ALLOWED, result.credentialStatus());
    }

    @Test
    void returnsPasswordReadyWithoutCreatingOrResettingCredential() {
        PasswordCredential password = PasswordCredential.restore(
                UUID.randomUUID(), IDENTITY_ID, CredentialType.PASSWORD,
                Argon2idPasswordHash.of(HASH), NOW.minusSeconds(60), null, null);
        identities.credentials = List.of(password);

        EnsureIdentityResult result = service.ensure(
                CALLER_ID, REQUEST_ID, "admin@example.test", null);

        assertEquals(IdentityCredentialStatus.PASSWORD_READY, result.credentialStatus());
        assertEquals(0, identities.credentialCreates);
        assertEquals(0, identities.invalidations);
    }

    @Test
    void returnsRecoveryRequiredForInitialOrHistoricalCredential() {
        PasswordCredential expiredInitial = PasswordCredential.restore(
                UUID.randomUUID(), IDENTITY_ID, CredentialType.INITIAL_PLATFORM_PASSWORD,
                Argon2idPasswordHash.of(HASH), NOW.minusSeconds(172800), NOW.minusSeconds(86400), null);
        PasswordCredential historicalPassword = PasswordCredential.restore(
                UUID.randomUUID(), IDENTITY_ID, CredentialType.PASSWORD,
                Argon2idPasswordHash.of(HASH), NOW.minusSeconds(120), null, NOW.minusSeconds(60));
        identities.credentials = List.of(expiredInitial, historicalPassword);

        EnsureIdentityResult result = service.ensure(
                CALLER_ID, REQUEST_ID, "admin@example.test", null);

        assertEquals(IdentityCredentialStatus.RECOVERY_REQUIRED, result.credentialStatus());
    }

    @Test
    void replaysStableResultAndRejectsFingerprintConflict() {
        identities.credentials = List.of();
        EnsureIdentityResult first = service.ensure(
                CALLER_ID, REQUEST_ID, " Admin@Example.Test ", "Tenant Admin");

        EnsureIdentityResult replay = service.ensure(
                CALLER_ID, REQUEST_ID, "admin@example.test", "Tenant Admin");
        assertEquals(first, replay);
        assertEquals(1, identities.findOrCreateCalls);

        assertThrows(EnsureIdentityRequestConflictException.class,
                () -> service.ensure(CALLER_ID, REQUEST_ID, "other@example.test", "Tenant Admin"));
    }

    @Test
    void rejectsNonUuidV7RequestBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ensure(CALLER_ID, UUID.randomUUID(), "admin@example.test", null));
        assertEquals(0, requests.lockCalls);
    }

    private static final class FakeIdentityProvisioningRepository implements IdentityProvisioningRepository {
        private IdentityProvisioningFact fact;
        private int lockCalls;

        @Override
        public void lockRequest(UUID callerClientId, UUID requestId) {
            lockCalls++;
        }

        @Override
        public Optional<IdentityProvisioningFact> find(UUID callerClientId, UUID requestId) {
            return Optional.ofNullable(fact)
                    .filter(value -> value.callerClientId().equals(callerClientId))
                    .filter(value -> value.requestId().equals(requestId));
        }

        @Override
        public void create(IdentityProvisioningFact fact) {
            this.fact = fact;
        }
    }

    private static final class FakeIdentityRepository implements IdentityRepository {
        private List<PasswordCredential> credentials = List.of();
        private Identity identity;
        private int findOrCreateCalls;
        private int credentialCreates;
        private int invalidations;

        @Override
        public Identity create(Identity identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Identity findOrCreate(Identity candidate) {
            findOrCreateCalls++;
            if (identity == null) {
                identity = candidate.identifiedBy(IDENTITY_ID);
            }
            return identity;
        }

        @Override
        public Optional<Identity> findByEmail(io.saasforge.iam.domain.identity.NormalizedEmail email) {
            return Optional.ofNullable(identity).filter(value -> value.email().equals(email));
        }

        @Override
        public Optional<Identity> findById(UUID identityId) {
            return Optional.ofNullable(identity).filter(value -> value.id().equals(identityId));
        }

        @Override
        public void lockIdentity(UUID identityId) {
        }

        @Override
        public Optional<PasswordCredential> createFirstPassword(PasswordCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PasswordCredential create(PasswordCredential credential) {
            credentialCreates++;
            throw new UnsupportedOperationException();
        }

        @Override
        public PasswordCredential replaceInitialPassword(
                PasswordCredential initialCredential, PasswordCredential passwordCredential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invalidate(UUID credentialId, Instant invalidatedAt) {
            invalidations++;
        }

        @Override
        public List<PasswordCredential> lockCredentials(UUID identityId) {
            return credentials;
        }

        @Override
        public List<PasswordCredential> findCredentials(UUID identityId) {
            return credentials;
        }

        @Override
        public Optional<PasswordCredential> findCredential(UUID credentialId) {
            return credentials.stream().filter(value -> credentialId.equals(value.id())).findFirst();
        }
    }
}
