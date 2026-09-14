package io.saasforge.iam.application.signing;

@FunctionalInterface
public interface JwsSigningInputFactory {
    JwsSigningInput create(String kid);
}
