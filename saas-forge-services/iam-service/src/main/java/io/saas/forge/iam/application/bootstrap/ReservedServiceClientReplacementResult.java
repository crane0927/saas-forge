package io.saas.forge.iam.application.bootstrap;

import java.util.UUID;

public record ReservedServiceClientReplacementResult(UUID clientId, Outcome outcome) {
    public enum Outcome {
        REPLACED,
        ALREADY_REPLACED
    }
}
