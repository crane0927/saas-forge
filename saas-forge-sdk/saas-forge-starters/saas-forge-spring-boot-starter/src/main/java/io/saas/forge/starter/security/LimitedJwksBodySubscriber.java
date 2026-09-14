package io.saas.forge.starter.security;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 在接收过程中限制 JWKS 响应大小，避免先完整分配不受限的响应。 */
final class LimitedJwksBodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<String> getBody() { return result; }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > 65536 - body.size()) {
                subscription.cancel();
                result.completeExceptionally(new IllegalStateException("IAM JWKS 响应过大"));
                return;
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            body.writeBytes(bytes);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) { result.completeExceptionally(failure); }

    @Override
    public void onComplete() { result.complete(body.toString(StandardCharsets.UTF_8)); }
}
