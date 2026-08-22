package io.saasforge.iam.config;

import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saasforge.iam.application.authentication.AccessibleMemberships;
import io.saasforge.iam.application.authentication.ContextSelectionService;
import io.saasforge.iam.application.authentication.ClientCredentialsTokenService;
import io.saasforge.iam.application.authentication.CompromisedPasswordChecker;
import io.saasforge.iam.application.authentication.InitialPasswordChangeService;
import io.saasforge.iam.application.authentication.LoginProtection;
import io.saasforge.iam.application.authentication.LoginSessionService;
import io.saasforge.iam.application.authentication.LogoutService;
import io.saasforge.iam.application.authentication.LogoutTransaction;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordChangedEventFactory;
import io.saasforge.iam.application.authentication.PasswordLoginService;
import io.saasforge.iam.application.authentication.PasswordEstablishedEventFactory;
import io.saasforge.iam.application.authentication.PasswordSetupChallengeIssuer;
import io.saasforge.iam.application.authentication.PasswordSetupService;
import io.saasforge.iam.application.authentication.RefreshTokenIssuer;
import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.application.authentication.RevocationIndexRecovery;
import io.saasforge.iam.application.authentication.PresentedAccessTokenVerifier;
import io.saasforge.iam.application.authentication.RefreshSessionService;
import io.saasforge.iam.application.authentication.RefreshRotationLease;
import io.saasforge.iam.application.authentication.RefreshRotationTransaction;
import io.saasforge.iam.application.authentication.RefreshReplayDetectedEventFactory;
import io.saasforge.iam.application.authentication.SessionStartedEventFactory;
import io.saasforge.iam.application.authentication.SessionRevokedEventFactory;
import io.saasforge.iam.application.authentication.UserAccessTokenIssuer;
import io.saasforge.iam.application.authentication.ServiceAccessTokenIssuer;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.authorization.PlatformRoleAuthorizationService;
import io.saasforge.iam.application.identity.EnsureIdentityService;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saasforge.iam.domain.identity.IdentityProvisioningRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.infrastructure.grpc.GrpcAccessibleMemberships;
import io.saasforge.iam.infrastructure.security.RedisLoginProtection;
import io.saasforge.iam.infrastructure.security.RedisRevocationIndex;
import io.saasforge.iam.infrastructure.security.RedisRefreshRotationLease;
import io.saasforge.iam.infrastructure.security.NimbusPresentedAccessTokenVerifier;
import io.saasforge.iam.infrastructure.security.ClasspathCompromisedPasswordChecker;
import io.saasforge.iam.infrastructure.security.IamJwtVerificationKeyResolver;
import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.client.GrpcChannelFactory;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom authenticationSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    UuidV7Generator uuidV7Generator(Clock clock, SecureRandom authenticationSecureRandom) {
        return new UuidV7Generator(clock, authenticationSecureRandom);
    }

    @Bean
    PasswordVerifier passwordVerifier() {
        return new PasswordVerifier();
    }

    @Bean
    PasswordSetupChallengeIssuer passwordSetupChallengeIssuer(SecureRandom authenticationSecureRandom) {
        return new PasswordSetupChallengeIssuer(authenticationSecureRandom);
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker(
            @Value("${saasforge.environment:dev}") String environment) {
        return new ClasspathCompromisedPasswordChecker(environment);
    }

    @Bean
    LoginProtection loginProtection(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment,
            @Value("${security.login-protection.failure-window:PT15M}") Duration failureWindow,
            @Value("${security.login-protection.maximum-failures:5}") int maximumFailures,
            @Value("${security.login-protection.lock-duration:PT15M}") Duration lockDuration) {
        return new RedisLoginProtection(redis, environment, failureWindow, maximumFailures, lockDuration);
    }

    @Bean
    UserAccessTokenIssuer userAccessTokenIssuer(
            JwtSigningService signingService,
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.access-token-ttl:PT15M}") Duration ttl) {
        return new UserAccessTokenIssuer(signingService, objectMapper, uuidV7Generator, clock, issuer, ttl);
    }

    @Bean
    ServiceAccessTokenIssuer serviceAccessTokenIssuer(
            JwtSigningService signingService,
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.service-access-token-ttl:PT5M}") Duration ttl) {
        return new ServiceAccessTokenIssuer(
                signingService, objectMapper, uuidV7Generator, clock, issuer, ttl);
    }

    @Bean
    ClientCredentialsTokenService clientCredentialsTokenService(
            OAuthClientRepository clients, ServiceAccessTokenIssuer tokens, Clock clock) {
        return new ClientCredentialsTokenService(clients, tokens, clock);
    }

    @Bean
    ServiceJwtVerificationKeyResolver serviceJwtVerificationKeyResolver(SigningKeyRepository signingKeys) {
        return new IamJwtVerificationKeyResolver(signingKeys);
    }

    @Bean
    ServiceAccessTokenVerifier serviceAccessTokenVerifier(
            ServiceJwtVerificationKeyResolver keys,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenVerifier(keys, clock, issuer, "saasforge-api", Duration.ofSeconds(30));
    }

    @Bean
    PlatformRoleAuthorizationService platformRoleAuthorizationService(
            PlatformRoleAssignmentRepository roles, Clock clock) {
        return new PlatformRoleAuthorizationService(roles, clock);
    }

    @Bean
    EnsureIdentityService ensureIdentityService(
            IdentityProvisioningRepository requests,
            IdentityRepository identities,
            Clock clock) {
        return new EnsureIdentityService(requests, identities, clock);
    }

    @Bean
    RefreshTokenIssuer refreshTokenIssuer(SecureRandom authenticationSecureRandom) {
        return new RefreshTokenIssuer(authenticationSecureRandom);
    }

    @Bean
    SessionStartedEventFactory sessionStartedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new SessionStartedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    PasswordChangedEventFactory passwordChangedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new PasswordChangedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    PasswordEstablishedEventFactory passwordEstablishedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new PasswordEstablishedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    SessionRevokedEventFactory sessionRevokedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new SessionRevokedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    RefreshReplayDetectedEventFactory refreshReplayDetectedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new RefreshReplayDetectedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    RefreshRotationLease refreshRotationLease(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment,
            @Value("${security.refresh.rotation-lease:PT5S}") Duration leaseDuration) {
        return new RedisRefreshRotationLease(redis, environment, leaseDuration);
    }

    @Bean
    RefreshRotationTransaction refreshRotationTransaction(
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            OutboxEventRepository outboxEvents,
            RefreshReplayDetectedEventFactory replayEventFactory,
            SessionRevokedEventFactory revokedEventFactory,
            @Value("${security.refresh.recovery-window:PT10S}") Duration recoveryWindow) {
        return new RefreshRotationTransaction(
                families, issuances, revocationIndex, outboxEvents,
                replayEventFactory, revokedEventFactory, recoveryWindow);
    }

    @Bean
    RevocationIndex revocationIndex(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment) {
        return new RedisRevocationIndex(redis, environment);
    }

    @Bean
    RevocationIndexRecovery revocationIndexRecovery(
            RevocationIndex index, AccessTokenIssuanceRepository issuances, Clock clock) {
        return new RevocationIndexRecovery(index, issuances, clock);
    }

    @Bean
    @ConditionalOnMissingBean(PresentedAccessTokenVerifier.class)
    PresentedAccessTokenVerifier presentedAccessTokenVerifier(
            SigningKeyRepository signingKeys,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new NimbusPresentedAccessTokenVerifier(signingKeys, clock, issuer);
    }

    @Bean
    LogoutTransaction logoutTransaction(
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            OutboxEventRepository outboxEvents,
            SessionRevokedEventFactory eventFactory) {
        return new LogoutTransaction(families, issuances, outboxEvents, eventFactory);
    }

    @Bean
    LogoutService logoutService(
            PresentedAccessTokenVerifier accessTokens,
            AccessTokenIssuanceRepository issuances,
            RefreshTokenIssuer refreshTokens,
            RevocationIndex revocationIndex,
            LogoutTransaction transaction,
            Clock clock) {
        return new LogoutService(accessTokens, issuances, refreshTokens, revocationIndex, transaction, clock);
    }

    @Bean
    LoginSessionService loginSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            AccessTokenIssuanceRepository accessTokenIssuances,
            OutboxEventRepository outboxEvents,
            SessionStartedEventFactory eventFactory) {
        return new LoginSessionService(
                platformRoles, refreshTokenFamilies, accessTokenIssuances, outboxEvents, eventFactory);
    }

    @Bean
    @ConditionalOnMissingBean(AccessibleMemberships.class)
    AccessibleMemberships accessibleMemberships(
            GrpcChannelFactory channels,
            @Value("${saasforge.iam.tenant-access-grpc-target:tenant-access}") String target) {
        return new GrpcAccessibleMemberships(
                AccessibleMembershipQueryServiceGrpc.newBlockingStub(channels.createChannel(target)));
    }

    @Bean
    PasswordLoginService passwordLoginService(
            IdentityRepository identities,
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            LoginProtection loginProtection,
            PasswordVerifier passwordVerifier,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            Clock clock) {
        return new PasswordLoginService(identities, platformRoles, accessibleMemberships, loginProtection, passwordVerifier,
                accessTokenIssuer, refreshTokenIssuer, sessionService, clock);
    }

    @Bean
    ContextSelectionService contextSelectionService(
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            Clock clock) {
        return new ContextSelectionService(
                accessibleMemberships, refreshTokenFamilies, accessTokenIssuer,
                refreshTokenIssuer, sessionService, clock);
    }

    @Bean
    RefreshSessionService refreshSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            RefreshRotationLease rotationLease,
            RefreshRotationTransaction rotationTransaction,
            Clock clock) {
        return new RefreshSessionService(
                platformRoles, accessibleMemberships, refreshTokenFamilies, accessTokenIssuer,
                refreshTokenIssuer, sessionService, rotationLease, rotationTransaction, clock);
    }

    @Bean
    InitialPasswordChangeService initialPasswordChangeService(
            IdentityRepository identities,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            RefreshTokenIssuer refreshTokenIssuer,
            PasswordPolicy passwordPolicy,
            CompromisedPasswordChecker compromisedPasswords,
            PasswordVerifier passwordVerifier,
            OutboxEventRepository outboxEvents,
            PasswordChangedEventFactory eventFactory,
            Clock clock) {
        return new InitialPasswordChangeService(
                identities, refreshTokenFamilies, refreshTokenIssuer, passwordPolicy, compromisedPasswords,
                passwordVerifier, outboxEvents, eventFactory, clock);
    }

    @Bean
    PasswordSetupService passwordSetupService(
            PasswordSetupChallengeRepository challenges,
            IdentityRepository identities,
            PasswordSetupChallengeIssuer challengeIssuer,
            PasswordPolicy passwordPolicy,
            CompromisedPasswordChecker compromisedPasswords,
            PasswordVerifier passwordVerifier,
            OutboxEventRepository outboxEvents,
            PasswordEstablishedEventFactory eventFactory,
            Clock clock) {
        return new PasswordSetupService(
                challenges, identities, challengeIssuer, passwordPolicy, compromisedPasswords,
                passwordVerifier, outboxEvents, eventFactory, clock);
    }
}
