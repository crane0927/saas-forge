package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ClientCredentialsTokenService {
    private final OAuthClientRepository clients;
    private final ServiceAccessTokenIssuer tokens;
    private final Clock clock;

    public ClientCredentialsTokenService(
            OAuthClientRepository clients, ServiceAccessTokenIssuer tokens, Clock clock) {
        this.clients = clients;
        this.tokens = tokens;
        this.clock = clock;
    }

    public IssuedServiceAccessToken issue(
            UUID clientId, String clientSecret, String grantType, String requestedScope) {
        if (!"client_credentials".equals(grantType)) {
            throw new ClientCredentialsGrantInvalidException();
        }
        OAuthClient client;
        try {
            client = clients.findActiveBySecretDigest(
                            ClientSecretDigest.fromPlaintext(clientSecret), clock.instant())
                    .filter(candidate -> candidate.id().equals(clientId))
                    .orElseThrow(ClientCredentialsInvalidException::new);
        } catch (IllegalArgumentException invalidSecret) {
            throw new ClientCredentialsInvalidException();
        }
        Set<OAuthScope> scopes = requestedScopes(requestedScope, client.allowedScopes());
        if (!client.allowedScopes().containsAll(scopes)) {
            throw new ClientCredentialsScopeRejectedException();
        }
        return tokens.issue(client.id(), scopes);
    }

    private static Set<OAuthScope> requestedScopes(String requestedScope, Set<OAuthScope> allowedScopes) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return allowedScopes;
        }
        try {
            return Arrays.stream(requestedScope.trim().split(" +"))
                    .map(OAuthScope::fromValue)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IllegalArgumentException unknownScope) {
            throw new ClientCredentialsScopeRejectedException();
        }
    }
}
