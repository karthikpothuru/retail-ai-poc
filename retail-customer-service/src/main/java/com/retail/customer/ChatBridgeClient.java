package com.retail.customer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.retail.customer.ChatFailure.Reason.*;

@Component
@Profile("poc")
@ConditionalOnProperty(name = "retail.chat.enabled", havingValue = "true")
class ChatBridgeClient {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_BODY = 32768;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(ProxySelector.of(null)).build();
    private final URI endpoint;
    private final String bridgeToken;
    private final Duration timeout;

    ChatBridgeClient(@Value("${retail.chat.adk-url:http://127.0.0.1:8091}") String origin,
                     @Value("${retail.chat.bridge-token:}") String bridgeToken,
                     @Value("${retail.chat.timeout:50s}") Duration timeout) {
        endpoint = loopbackOrigin(origin).resolve("/internal/v1/retail/ask");
        if (!bridgeToken.matches("[A-Za-z0-9_-]{43}") || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(125)) > 0) {
            throw new IllegalArgumentException("Invalid chat bridge configuration");
        }
        this.bridgeToken = bridgeToken;
        this.timeout = timeout;
    }

    static URI loopbackOrigin(String value) {
        var uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1
                || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getFragment() != null || !uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("An explicit literal loopback HTTP origin is required");
        }
        return uri;
    }

    ChatReply ask(String message, String sessionCookie) {
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            var body = JSON.writeValueAsBytes(Map.of("message", message, "sessionCookie", sessionCookie));
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + bridgeToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            future = client.sendAsync(request, ignored -> new BoundedBody());
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			System.out.println( "[CHAT-DIAGNOSTIC] ADK bridge HTTP status: "    + response.statusCode());
            return switch (response.statusCode()) {
                case 200 -> decode(response, sessionCookie);
                case 403, 409 -> throw new ChatFailure(SESSION_CHANGED);
                case 504 -> throw new ChatFailure(ADK_TIMEOUT);
                case 502 -> throw new ChatFailure(INVALID_ADK_RESPONSE);
                default -> throw new ChatFailure(ADK_UNAVAILABLE);
            };
        } catch (TimeoutException expired) {
            if (future != null) future.cancel(true);
            throw new ChatFailure(ADK_TIMEOUT);
        } catch (InterruptedException interrupted) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ChatFailure(ADK_UNAVAILABLE);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ChatFailure invalid) throw invalid;
            if (failure.getCause() instanceof HttpTimeoutException) throw new ChatFailure(ADK_TIMEOUT);
            throw new ChatFailure(ADK_UNAVAILABLE);
        } catch (IOException invalid) {
            throw new ChatFailure(INVALID_ADK_RESPONSE);
        }
    }

    private ChatReply decode(HttpResponse<byte[]> response, String sessionCookie) throws IOException {
        if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip()
                .equalsIgnoreCase("application/json")) {
            throw new ChatFailure(INVALID_ADK_RESPONSE);
        }
        var reply = ChatReply.decode(JSON.readTree(response.body()));
        if (reply.answer().contains(sessionCookie) || reply.answer().contains(bridgeToken)) {
            throw new ChatFailure(INVALID_ADK_RESPONSE);
        }
        return reply;
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription next) {
            subscription = next;
            next.request(Long.MAX_VALUE);
        }
        @Override public void onNext(List<ByteBuffer> parts) {
            for (var part : parts) {
                if (part.remaining() > MAX_BODY - body.size()) {
                    result.completeExceptionally(new ChatFailure(INVALID_ADK_RESPONSE));
                    subscription.cancel();
                    return;
                }
                var bytes = new byte[part.remaining()];
                part.get(bytes);
                body.writeBytes(bytes);
            }
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }
}
