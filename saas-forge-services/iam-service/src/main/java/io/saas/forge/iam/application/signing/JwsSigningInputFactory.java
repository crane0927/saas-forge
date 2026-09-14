package io.saas.forge.iam.application.signing;

@FunctionalInterface
public interface JwsSigningInputFactory {
    JwsSigningInput create(String kid);
}
