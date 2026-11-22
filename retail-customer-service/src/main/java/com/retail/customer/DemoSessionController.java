package com.retail.customer;

import com.retail.customer.CustomerModels.DemoSelection;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("poc")
@RestController
class DemoSessionController {
    private final DemoSessionIdentity sessions;

    DemoSessionController(DemoSessionIdentity sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/v1/demo/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@Valid @RequestBody DemoSelection input) {
        sessions.select(input.customerId());
    }

    @DeleteMapping("/api/v1/demo/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear() {
        sessions.clear();
    }
}
