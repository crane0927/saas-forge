package io.saasforge.iam.application.bootstrap;

import java.util.Map;
import java.util.UUID;

public record ReservedServiceClientBootstrapResult(Map<ReservedServiceClient, ClientResult> clients) {
    public ReservedServiceClientBootstrapResult {
        clients = Map.copyOf(clients);
    }

    public enum Outcome {
        INITIALIZED,
        ALREADY_INITIALIZED
    }

    public record ClientResult(UUID clientId, Outcome outcome) {
    }
}
