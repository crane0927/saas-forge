package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.shared.Sha256Digest;

public record RefreshTokenMaterial(String value, Sha256Digest digest) {
}
