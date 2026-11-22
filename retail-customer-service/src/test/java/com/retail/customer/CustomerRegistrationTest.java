package com.retail.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=0.0.0.0")
@ActiveProfiles("poc")
class CustomerRegistrationTest {
    private static final String CUSTOMERS = "/api/v1/customers";
    private static final String DEMO_SESSION = "/api/v1/demo/session";
    private static final String SCHEMA = "phase81_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerService service;
    @Autowired TomcatServletWebServerFactory serverFactory;
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("RETAIL_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("RETAIL_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("RETAIL_TEST_DB_PASSWORD"));
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("Set " + name + " to run PostgreSQL integration tests");
        }
        return value;
    }

    @BeforeEach
    void setupMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterAll
    static void removeTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void registersAndNormalizesEmail() throws Exception {
        String email = syntheticEmail();
        MvcResult result = mvc.perform(registration(" " + email.toUpperCase(java.util.Locale.ROOT) + " "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.givenName").value("Asha"))
                .andReturn();
        UUID id = customerId(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(CUSTOMERS + "/" + id);
        assertThat(service.profile(id).email()).isEqualTo(email);
    }

    @Test
    void rejectsDuplicateNormalizedEmail() throws Exception {
        String email = syntheticEmail();
        register(email);
        mvc.perform(registration(" " + email.toUpperCase(java.util.Locale.ROOT) + " "))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidRegistration() throws Exception {
        mvc.perform(registration("invalid-email")).andExpect(status().isBadRequest());
        mvc.perform(post(CUSTOMERS).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"valid@example.test","givenName":"   ","familyName":"Rao"}
                        """)).andExpect(status().isBadRequest());
    }

    @Test
    void retrievesOnlyTheSelectedProfileAndClearsSession() throws Exception {
        UUID id = register(syntheticEmail());
        UUID other = register(syntheticEmail());
        mvc.perform(get(CUSTOMERS + "/" + id)).andExpect(status().isForbidden());
        MockHttpSession session = select(id);
        mvc.perform(get(CUSTOMERS + "/" + id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(id.toString()));
        mvc.perform(get(CUSTOMERS + "/" + other).session(session)).andExpect(status().isForbidden());
        mvc.perform(delete(DEMO_SESSION).session(session)).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void rejectsSelectionForNonSyntheticCustomer() throws Exception {
        UUID id = register(UUID.randomUUID() + "@example.com");
        mvc.perform(selection(id)).andExpect(status().isNotFound());
    }

    @Test
    void sessionContextUsesOnlyTheServerSelectionAndRotatesOnSwitch() throws Exception {
        String path = DEMO_SESSION + "/context";
        var first = register(syntheticEmail());
        var second = register(syntheticEmail());
        mvc.perform(get(path).header("X-Customer-Id", second).param("customerId", second.toString()))
                .andExpect(status().isForbidden());
        var original = select(first);
        var before = mvc.perform(get(path).session(original).header("X-Customer-Id", second)
                        .param("customerId", second.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.customerId").value(first.toString()))
                .andExpect(jsonPath("$.personalizationConsent").value(false))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andReturn();
        var oldSelection = json.readTree(before.getResponse().getContentAsString()).get("selectionId").asText();
        var switched = (MockHttpSession) mvc.perform(selection(second).session(original))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        assertThat(original.isInvalid()).isTrue();
        var after = mvc.perform(get(path).session(switched)).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(second.toString())).andReturn();
        assertThat(json.readTree(after.getResponse().getContentAsString()).get("selectionId").asText())
                .isNotEqualTo(oldSelection);
        mvc.perform(delete(DEMO_SESSION).session(switched)).andExpect(status().isNoContent());
        assertThat(switched.isInvalid()).isTrue();
    }

    @Test
    void changesConsentEpochOnlyWhenConsentChanges() throws Exception {
        var id = register(syntheticEmail());
        var session = select(id);
        var initial = service.personalization(id);
        assertThat(initial.personalizationConsent()).isFalse();
        putPreferences(id, session, true);
        var granted = service.personalization(id);
        assertThat(granted.personalizationConsent()).isTrue();
        assertThat(granted.consentEpoch()).isNotEqualTo(initial.consentEpoch());
        putPreferences(id, session, true);
        assertThat(service.personalization(id)).isEqualTo(granted);
        putPreferences(id, session, false);
        assertThat(service.personalization(id).consentEpoch()).isNotEqualTo(granted.consentEpoch());
        putPreferences(id, session, true);
        assertThat(service.personalization(id).consentEpoch()).isNotEqualTo(granted.consentEpoch());
    }

    @Test
    void updatesOnlySuppliedProfileFields() throws Exception {
        UUID id = register(syntheticEmail());
        MockHttpSession session = select(id);
        mvc.perform(patch(CUSTOMERS + "/" + id).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"givenName":" Meera "}
                        """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.givenName").value("Meera"))
                .andExpect(jsonPath("$.familyName").value("Rao"));
        assertThat(service.profile(id).givenName()).isEqualTo("Meera");
        mvc.perform(patch(CUSTOMERS + "/" + id).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"givenName":"   "}
                        """)).andExpect(status().isBadRequest());
    }

    @Test
    void storesConsentAndMarketingDefaults() throws Exception {
        UUID id = register(syntheticEmail());
        mvc.perform(get(preferencesPath(id)).session(select(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.marketingOptIn").value(false))
                .andExpect(jsonPath("$.personalizationConsent").value(false));
    }
	
	@Test
    void newCustomerHasAiProcessingConsentDisabledByDefault() throws Exception {
    UUID id = register(syntheticEmail());

    var consent = service.aiProcessingConsent(id);

    assertThat(consent.enabled()).isFalse();
    assertThat(consent.consentEpoch()).isNotNull();
}

@Test
void aiProcessingConsentIsIsolatedBetweenCustomers() throws Exception {
    UUID firstCustomer = register(syntheticEmail());
    UUID secondCustomer = register(syntheticEmail());

    var firstConsent = service.aiProcessingConsent(firstCustomer);
    var secondConsent = service.aiProcessingConsent(secondCustomer);

    assertThat(firstConsent.enabled()).isFalse();
    assertThat(secondConsent.enabled()).isFalse();

    assertThat(firstConsent.consentEpoch())
            .isNotEqualTo(secondConsent.consentEpoch());
}
	
@Test
void aiProcessingConsentEpochRotatesOnlyWhenConsentChanges()
        throws Exception {

    UUID id = register(syntheticEmail());

    var initial = service.aiProcessingConsent(id);
    assertThat(initial.enabled()).isFalse();

    var granted = service.updateAiProcessingConsent(id, true);
    assertThat(granted.enabled()).isTrue();
    assertThat(granted.consentEpoch())
            .isNotEqualTo(initial.consentEpoch());

    var repeated = service.updateAiProcessingConsent(id, true);
    assertThat(repeated).isEqualTo(granted);

    var withdrawn = service.updateAiProcessingConsent(id, false);
    assertThat(withdrawn.enabled()).isFalse();
    assertThat(withdrawn.consentEpoch())
            .isNotEqualTo(granted.consentEpoch());
}
	
	@Test
void auditsAiProcessingConsentUpdates() throws Exception {
    UUID id = register(syntheticEmail());

    service.updateAiProcessingConsent(id, true);
    service.updateAiProcessingConsent(id, false);

    var events = jdbc.queryForList("""
            SELECT event_type
            FROM customer_audit
            WHERE customer_id = ?
            ORDER BY audit_id
            """, String.class, id);

    assertThat(events).containsExactly(
            "REGISTERED",
            "AI_PROCESSING_CONSENT_UPDATED",
            "AI_PROCESSING_CONSENT_UPDATED"
    );
}
@Test
void updatingAiConsentDoesNotAffectAnotherCustomer()
        throws Exception {

    UUID customerA = register(syntheticEmail());
    UUID customerB = register(syntheticEmail());

    var customerBBefore = service.aiProcessingConsent(customerB);

    var customerAUpdated =
            service.updateAiProcessingConsent(customerA, true);

    var customerBAfter = service.aiProcessingConsent(customerB);

    assertThat(customerAUpdated.enabled()).isTrue();

    assertThat(customerBAfter.enabled()).isFalse();

    assertThat(customerBAfter.consentEpoch())
            .isEqualTo(customerBBefore.consentEpoch());
}
    @Test
    void persistsPreferencesAndAllowsConsentWithdrawal() throws Exception {
        UUID id = register(syntheticEmail());
        MockHttpSession session = select(id);
        putPreferences(id, session, true);
        mvc.perform(get(preferencesPath(id)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en-IN"))
                .andExpect(jsonPath("$.marketingOptIn").value(true))
                .andExpect(jsonPath("$.personalizationConsent").value(true));
        putPreferences(id, session, false);
        assertThat(service.preferences(id).personalizationConsent()).isFalse();
        mvc.perform(put(preferencesPath(id)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"locale":"en-IN","marketingOptIn":true}
                        """)).andExpect(status().isBadRequest());
        mvc.perform(put(preferencesPath(id)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"locale":"en-IN","marketingOptIn":true,"personalizationConsent":"true"}
                        """)).andExpect(status().isBadRequest());
    }

    @Test
    void persistsAuditForSuccessfulWrites() throws Exception {
        UUID id = register(syntheticEmail());
        MockHttpSession session = select(id);
        mvc.perform(patch(CUSTOMERS + "/" + id).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"familyName":"Shah"}
                        """)).andExpect(status().isOk());
        putPreferences(id, session, true);
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM customer_audit WHERE customer_id = ? ORDER BY audit_id
                """, String.class, id)).containsExactly("REGISTERED", "PROFILE_UPDATED", "PREFERENCES_UPDATED");
    }

    @Test
    void acceptsExactlyOneConcurrentRegistration() throws Exception {
        String email = syntheticEmail();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = List.of(email, " " + email.toUpperCase(java.util.Locale.ROOT) + " ").stream()
                    .map(input -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Registration workers did not start");
                        }
                        return mvc.perform(registration(input)).andReturn().getResponse().getStatus();
                    })).toList();
            boolean bothReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            assertThat(List.of(requests.get(0).get(15, TimeUnit.SECONDS),
                    requests.get(1).get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM customer_account
                JOIN customer_profile USING (customer_id)
                JOIN customer_preferences USING (customer_id)
                JOIN customer_audit USING (customer_id)
                WHERE email = ?
                """, Integer.class, email)).isEqualTo(1);
    }

    @Test
    void rollsBackAllRegistrationWritesWhenAuditFails() {
        String email = syntheticEmail();
        boolean installed = false;
        try {
            jdbc.execute("""
                    ALTER TABLE customer_audit ADD CONSTRAINT test_reject_registration
                    CHECK (event_type <> 'REGISTERED') NOT VALID
                    """);
            installed = true;
            assertThatThrownBy(() -> service.register(new CustomerModels.Registration(email, "Asha", "Rao")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasStackTraceContaining("test_reject_registration");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_account WHERE email = ?",
                    Integer.class, email)).isZero();
        } finally {
            if (installed) {
                jdbc.execute("ALTER TABLE customer_audit DROP CONSTRAINT test_reject_registration");
            }
        }
        assertThat(service.register(new CustomerModels.Registration(email, "Asha", "Rao")).email())
                .isEqualTo(email);
    }

    @Test
    void forcesLoopbackEvenWhenServerAddressIsOverridden() {
        assertThat(serverFactory.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
    }

    private UUID register(String email) throws Exception {
        return customerId(mvc.perform(registration(email)).andExpect(status().isCreated()).andReturn());
    }

    private UUID customerId(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("customerId").asText());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder registration(String email)
            throws Exception {
        return post(CUSTOMERS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "givenName", "Asha", "familyName", "Rao")));
    }

    private MockHttpSession select(UUID id) throws Exception {
        return (MockHttpSession) mvc.perform(selection(id)).andExpect(status().isNoContent()).andReturn()
                .getRequest().getSession(false);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder selection(UUID id) {
        return post(DEMO_SESSION).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId":"%s"}
                        """.formatted(id));
    }

    private void putPreferences(UUID id, MockHttpSession session, boolean consent) throws Exception {
        mvc.perform(put(preferencesPath(id)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"locale":"en-IN","marketingOptIn":true,"personalizationConsent":%b}
                        """.formatted(consent))).andExpect(status().isOk());
    }

    private static String preferencesPath(UUID id) {
        return CUSTOMERS + "/" + id + "/preferences";
    }

    private static String syntheticEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
