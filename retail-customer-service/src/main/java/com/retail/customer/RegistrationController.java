package com.retail.customer;

import com.retail.customer.CustomerModels.Profile;
import com.retail.customer.CustomerModels.Registration;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
class RegistrationController {
    private final CustomerService customers;

    RegistrationController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/api/v1/customers")
    ResponseEntity<Profile> register(@Valid @RequestBody Registration input) {
        Profile profile = customers.register(input);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + profile.customerId())).body(profile);
    }
}
