package com.retail.customer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties = "retail.chat.enabled=true")
@ActiveProfiles("non-poc")
class NonPocRoutesTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerService customers;

    @Test
    void hidesAllDemoRoutesAndKeepsRegistrationAvailable() throws Exception {
        UUID id = UUID.randomUUID();
        String profile = "/api/v1/customers/" + id;
        String preferences = profile + "/preferences";
        mvc.perform(get(profile)).andExpect(status().isNotFound());
        mvc.perform(patch(profile)).andExpect(status().isNotFound());
        mvc.perform(get(preferences)).andExpect(status().isNotFound());
        mvc.perform(put(preferences)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/demo/session")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/demo/session")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/demo/session/context")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/chat/csrf")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/chat/ask")).andExpect(status().isNotFound());

        when(customers.register(any())).thenReturn(new CustomerModels.Profile(id, "asha@example.test", "Asha", "Rao"));
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"asha@example.test","givenName":"Asha","familyName":"Rao"}
                        """)).andExpect(status().isCreated());
    }
}
