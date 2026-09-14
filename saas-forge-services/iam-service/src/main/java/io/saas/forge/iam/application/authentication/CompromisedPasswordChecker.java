package io.saas.forge.iam.application.authentication;

public interface CompromisedPasswordChecker {
    boolean isCompromised(String normalizedPassword);
}
