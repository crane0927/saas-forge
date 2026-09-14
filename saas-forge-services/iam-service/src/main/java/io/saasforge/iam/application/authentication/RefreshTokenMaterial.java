package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.shared.Sha256Digest;

public record RefreshTokenMaterial(String value, Sha256Digest digest) {
}
