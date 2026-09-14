package io.saas.forge.iam.application.identity;

import io.saas.forge.iam.domain.identity.IdentityCredentialStatus;
import java.util.UUID;

public record EnsureIdentityResult(UUID identityId, IdentityCredentialStatus credentialStatus) {
}
