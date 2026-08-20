package io.saasforge.iam.config;

import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saasforge.iam.application.authentication.AccessibleMemberships;
import io.saasforge.iam.application.authentication.LoginProtection;
import io.saasforge.iam.application.authentication.LoginSessionService;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.application.authentication.PasswordLoginService;
import io.saasforge.iam.application.authentication.RefreshTokenIssuer;
import io.saasforge.iam.application.authentication.SessionStartedEventFactory;
import io.saasforge.iam.application.authentication.UserAccessTokenIssuer;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.infrastructure.grpc.GrpcAccessibleMemberships;
import io.saasforge.iam.infrastructure.security.RedisLoginProtection;
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
}
