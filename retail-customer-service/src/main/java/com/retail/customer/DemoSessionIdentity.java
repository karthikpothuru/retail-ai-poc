package com.retail.customer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
@Profile("poc")
class DemoSessionIdentity implements CustomerIdentityProvider {
    record Selection(UUID customerId, UUID selectionId, UUID chatCsrfToken) {}

    private static final String CUSTOMER_ATTRIBUTE = DemoSessionIdentity.class.getName() + ".customerId";
    private final HttpServletRequest request;
    private final CustomerService customers;

    DemoSessionIdentity(HttpServletRequest request, CustomerService customers) {
        this.request = request;
        this.customers = customers;
    }

    void select(UUID candidate) {
        if (!customers.isSynthetic(candidate)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic customer not found");
        }
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true).setAttribute(CUSTOMER_ATTRIBUTE,
                new Selection(candidate, UUID.randomUUID(), UUID.randomUUID()));
    }

    @Override
    public UUID requireCustomerId() {
        return requireSelection().customerId();
    }

    Selection requireSelection() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                if (session.getAttribute(CUSTOMER_ATTRIBUTE) instanceof Selection selected) {
                    return selected;
                }
            } catch (IllegalStateException invalidated) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo session is inactive");
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Select a synthetic customer first");
    }

    void clear() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
