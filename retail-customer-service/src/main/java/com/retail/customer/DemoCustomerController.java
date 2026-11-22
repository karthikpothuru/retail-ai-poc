package com.retail.customer;

import com.retail.customer.CustomerModels.Preferences;
import com.retail.customer.CustomerModels.ProfilePatch;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;

import java.util.UUID;

@Profile("poc")
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
class DemoCustomerController {
    private final CustomerService customers;
    private final CustomerIdentityProvider identity;
	private final String portalOrigin;

  DemoCustomerController(
        CustomerService customers,
        CustomerIdentityProvider identity,
        @Value("${retail.chat.portal-origin:http://127.0.0.1:5173}")
        String portalOrigin) {

    this.customers = customers;
    this.identity = identity;
    this.portalOrigin =
            ChatBridgeClient.loopbackOrigin(portalOrigin).toString();
}

    @GetMapping
    CustomerModels.Profile profile(@PathVariable UUID customerId) {
        return customers.profile(requireSelectedCustomer(customerId));
    }

    @PatchMapping
    CustomerModels.Profile update(@PathVariable UUID customerId, @Valid @RequestBody ProfilePatch patch) {
        return customers.updateProfile(requireSelectedCustomer(customerId), patch);
    }

    @GetMapping("/preferences")
    Preferences preferences(@PathVariable UUID customerId) {
        return customers.preferences(requireSelectedCustomer(customerId));
    }

    @PutMapping("/preferences")
    Preferences updatePreferences(@PathVariable UUID customerId, @Valid @RequestBody Preferences input) {
        return customers.updatePreferences(requireSelectedCustomer(customerId), input);
    }
	
	@GetMapping("/ai-processing-consent")
CustomerService.AiProcessingConsent aiProcessingConsent(
        @PathVariable UUID customerId) {

    return customers.aiProcessingConsent(
            requireSelectedCustomer(customerId));
}



@PutMapping("/ai-processing-consent")
CustomerService.AiProcessingConsent updateAiProcessingConsent(
        @PathVariable UUID customerId,
        @Valid @RequestBody AiProcessingConsentRequest input,
        HttpServletRequest request) {

    UUID trustedCustomerId = requireSelectedCustomer(customerId);
	String origin = request.getHeader("Origin");
String site = request.getHeader("Sec-Fetch-Site");

if (!portalOrigin.equals(origin)
        || !"1".equals(request.getHeader("X-Retail-Portal"))
        || (site != null && !"same-origin".equals(site))) {

    throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Invalid request origin");
}

    String provided = request.getHeader("X-Retail-CSRF");

    if (!(identity instanceof DemoSessionIdentity demoIdentity)) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Demo session required");
    }

    String expected = demoIdentity.requireSelection()
            .chatCsrfToken().toString();

    if (provided == null || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8))) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Invalid CSRF token");
    }

    return customers.updateAiProcessingConsent(
            trustedCustomerId, input.enabled());
}

record AiProcessingConsentRequest(
        @NotNull Boolean enabled) {}

    private UUID requireSelectedCustomer(UUID target) {
        UUID selected = identity.requireCustomerId();
        if (!selected.equals(target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Target does not match the demo selection");
        }
        return selected;
    }
}
