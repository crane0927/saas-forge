package io.saasforge.iam.application.authentication;

public interface CompromisedPasswordChecker {
    boolean isCompromised(String normalizedPassword);
}
