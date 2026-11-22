package com.retail.customer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.retail.customer.ChatFailure.Reason.*;

@Profile("poc")
@RestController
@ConditionalOnProperty(name = "retail.chat.enabled", havingValue = "true")
class ChatController {
    private static final int MAX_BODY = 8192;
    record Csrf(String csrfToken) {}

    private final DemoSessionIdentity identity;
    private final CustomerService customers;
    private final ChatBridgeClient adk;
    private final String portalOrigin;

    ChatController(DemoSessionIdentity identity, CustomerService customers, ChatBridgeClient adk,
                   @Value("${retail.chat.portal-origin:http://127.0.0.1:5173}") String portalOrigin) {
        this.identity = identity;
        this.customers = customers;
        this.adk = adk;
        this.portalOrigin = ChatBridgeClient.loopbackOrigin(portalOrigin).toString();
    }

    @GetMapping("/api/v1/chat/csrf")
    ResponseEntity<Csrf> csrf(HttpServletRequest request) {
        guard(request, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Csrf(selection(request).chatCsrfToken().toString()));
    }

    @PostMapping("/api/v1/chat/ask")
    ResponseEntity<ChatReply> ask(HttpServletRequest request) {
        guard(request, true);
        var selected = selection(request);
        String provided = request.getHeader("X-Retail-CSRF");
        if (provided == null || provided.length() != 36 || !MessageDigest.isEqual(
                selected.chatCsrfToken().toString().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ChatFailure(CSRF_REJECTED);
        }
        String message = message(request);
        var before = customers.personalization(selected.customerId());
        var reply = adk.ask(message, request.getRequestedSessionId());

        DemoSessionIdentity.Selection current;
        try {
            current = selection(request);
        } catch (ChatFailure lost) {
            throw new ChatFailure(SESSION_CHANGED);
        }
        if (!selected.equals(current)) throw new ChatFailure(SESSION_CHANGED);
        var after = customers.personalization(current.customerId());
        // Check selection again after the database round-trip.
        try {
            if (!selected.equals(selection(request))) throw new ChatFailure(SESSION_CHANGED);
        } catch (ChatFailure lost) {
            throw new ChatFailure(SESSION_CHANGED);
        }
        if (reply.personalized() && (!before.personalizationConsent() || !after.personalizationConsent()
                || !before.consentEpoch().equals(after.consentEpoch()))) {
            throw new ChatFailure(SESSION_CHANGED);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reply);
    }

    private DemoSessionIdentity.Selection selection(HttpServletRequest request) {
        String cookie = request.getRequestedSessionId();
        if (!request.isRequestedSessionIdFromCookie() || cookie == null || !cookie.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new ChatFailure(SESSION_REQUIRED);
        }
        try {
            return identity.requireSelection();
        } catch (ResponseStatusException inactive) {
            throw new ChatFailure(SESSION_REQUIRED);
        }
    }

    private void guard(HttpServletRequest request, boolean write) {
        String origin = request.getHeader("Origin");
        String site = request.getHeader("Sec-Fetch-Site");
        if (!"1".equals(request.getHeader("X-Retail-Portal"))
                || (write || origin != null) && !portalOrigin.equals(origin)
                || site != null && !"same-origin".equals(site)) {
            throw new ChatFailure(CSRF_REJECTED);
        }
        if (request.getQueryString() != null || request.getHeader("X-Customer-Id") != null
                || request.getHeader("X-Memory-Namespace") != null) {
            throw new ChatFailure(INVALID_REQUEST);
        }
    }

    private static String message(HttpServletRequest request) {
        var type = request.getContentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new ChatFailure(INVALID_REQUEST);
        }
        try {
            var body = request.getInputStream().readNBytes(MAX_BODY + 1);
            var input = body.length <= MAX_BODY ? ChatBridgeClient.JSON.readTree(body) : null;
            if (input == null || !input.isObject() || input.size() != 1 || !input.path("message").isTextual()) {
                throw new ChatFailure(INVALID_REQUEST);
            }
            String text = input.path("message").textValue();
            if (text.isBlank() || text.length() > 1200) throw new ChatFailure(INVALID_REQUEST);
            return text.strip();
        } catch (IOException malformed) {
            throw new ChatFailure(INVALID_REQUEST);
        }
    }

    @ExceptionHandler(ChatFailure.class)
    ResponseEntity<ChatReply> error(ChatFailure failure) {
        return ResponseEntity.status(failure.reason.status).cacheControl(CacheControl.noStore())
                .body(ChatReply.error(failure.reason));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ChatReply> dependencyUnavailable() {
        return error(new ChatFailure(ADK_UNAVAILABLE));
    }
}
