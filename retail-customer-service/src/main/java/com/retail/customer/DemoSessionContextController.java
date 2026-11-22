package com.retail.customer;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Profile("poc")
@RestController
class DemoSessionContextController {
   record SessionContext(
        UUID customerId,
        UUID selectionId,
        boolean personalizationConsent,
        UUID consentEpoch,
        boolean aiProcessingConsent,
        UUID aiProcessingEpoch) {}

    private final DemoSessionIdentity identity;
    private final CustomerService customers;

    DemoSessionContextController(DemoSessionIdentity identity, CustomerService customers) {
        this.identity = identity;
        this.customers = customers;
    }

   @GetMapping("/api/v1/demo/session/context")
ResponseEntity<SessionContext> context() {
    var selected = identity.requireSelection();

    var personalization = customers.personalization(
            selected.customerId());

    var aiConsent = customers.aiProcessingConsent(
            selected.customerId());

    // Verify that the selected session has not changed
    // while retrieving consent information.
    if (!selected.equals(identity.requireSelection())) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Demo session changed");
    }

    return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new SessionContext(
                    selected.customerId(),
                    selected.selectionId(),
                    personalization.personalizationConsent(),
                    personalization.consentEpoch(),
                    aiConsent.enabled(),
                    aiConsent.consentEpoch()
            ));
}
}
