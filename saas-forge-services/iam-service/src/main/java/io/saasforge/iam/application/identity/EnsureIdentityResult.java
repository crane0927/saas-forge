package io.saasforge.iam.application.identity;

import io.saasforge.iam.domain.identity.IdentityCredentialStatus;
import java.util.UUID;

public record EnsureIdentityResult(UUID identityId, IdentityCredentialStatus credentialStatus) {
}
