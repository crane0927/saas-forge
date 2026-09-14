package io.saas.forge.iam.application.authentication;

public final class RevocationFenceConflictException extends RuntimeException {
    public RevocationFenceConflictException() {
        super("Revocation Fence 目标或 generation 冲突");
    }
}
