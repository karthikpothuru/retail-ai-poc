package com.retail.customer;

import org.springframework.http.HttpStatus;

final class ChatFailure extends RuntimeException {
    enum Reason {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Send a nonblank message of at most 1,200 characters."),
        SESSION_REQUIRED(HttpStatus.FORBIDDEN, "Select a synthetic customer first."),
        CSRF_REJECTED(HttpStatus.FORBIDDEN, "The chat request failed the portal security check."),
        SESSION_CHANGED(HttpStatus.CONFLICT, "The session or consent changed. Retry with the current session."),
        ADK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The retail assistant is temporarily unavailable."),
        ADK_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The retail assistant did not finish in time."),
        INVALID_ADK_RESPONSE(HttpStatus.BAD_GATEWAY, "The retail assistant returned an invalid response.");

        final HttpStatus status;
        final String message;

        Reason(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    final Reason reason;

    ChatFailure(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }
}
