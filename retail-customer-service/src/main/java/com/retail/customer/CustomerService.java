package com.retail.customer;

import com.retail.customer.CustomerModels.Preferences;
import com.retail.customer.CustomerModels.Profile;
import com.retail.customer.CustomerModels.ProfilePatch;
import com.retail.customer.CustomerModels.Registration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Types;
import java.util.UUID;

@Service
class CustomerService {
    record Personalization(boolean personalizationConsent, UUID consentEpoch) {}
	record AiProcessingConsent(boolean enabled, UUID consentEpoch) {}

    private final JdbcClient jdbc;

    CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Profile register(Registration input) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO customer_account (customer_id, email) VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT customer_email_unique DO NOTHING
                """).params(id, input.email()).update();
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
        jdbc.sql("INSERT INTO customer_profile (customer_id, given_name, family_name) VALUES (?, ?, ?)")
                .params(id, input.givenName(), input.familyName()).update();
        jdbc.sql("INSERT INTO customer_preferences (customer_id) VALUES (?)").param(id).update();
        audit(id, "REGISTERED");
        return new Profile(id, input.email(), input.givenName(), input.familyName());
    }

    boolean isSynthetic(UUID id) {
        return jdbc.sql("SELECT email FROM customer_account WHERE customer_id = ?")
                .param(id).query(String.class).optional()
                .map(email -> email.substring(email.lastIndexOf('@') + 1))
                .filter(domain -> domain.length() > 5 && domain.endsWith(".test"))
                .isPresent();
    }

    public Profile profile(UUID trustedCustomerId) {
        return jdbc.sql("""
                SELECT customer_id, email, given_name, family_name
                FROM customer_account JOIN customer_profile USING (customer_id)
                WHERE customer_id = ?
                """).param(trustedCustomerId)
                .query((rs, row) -> new Profile(rs.getObject("customer_id", UUID.class),
                        rs.getString("email"), rs.getString("given_name"), rs.getString("family_name")))
                .optional().orElseThrow(CustomerService::customerNotFound);
    }

    @Transactional
    public Profile updateProfile(UUID trustedCustomerId, ProfilePatch patch) {
        if (patch.givenName() == null && patch.familyName() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply at least one name");
        }
        requireUpdated(jdbc.sql("""
                UPDATE customer_profile SET given_name = COALESCE(:given, given_name),
                    family_name = COALESCE(:family, family_name) WHERE customer_id = :id
                """).param("given", patch.givenName(), Types.VARCHAR)
                .param("family", patch.familyName(), Types.VARCHAR)
                .param("id", trustedCustomerId).update());
        audit(trustedCustomerId, "PROFILE_UPDATED");
        return profile(trustedCustomerId);
    }

    public Preferences preferences(UUID trustedCustomerId) {
        return jdbc.sql("""
                SELECT locale, marketing_opt_in, personalization_consent
                FROM customer_preferences WHERE customer_id = ?
                """).param(trustedCustomerId)
                .query((rs, row) -> new Preferences(rs.getString("locale"),
                        rs.getBoolean("marketing_opt_in"), rs.getBoolean("personalization_consent")))
                .optional().orElseThrow(CustomerService::customerNotFound);
    }

    Personalization personalization(UUID trustedCustomerId) {
        return jdbc.sql("""
                SELECT personalization_consent, personalization_epoch
                FROM customer_preferences WHERE customer_id = ?
                """).param(trustedCustomerId)
                .query((rs, row) -> new Personalization(rs.getBoolean("personalization_consent"),
                        rs.getObject("personalization_epoch", UUID.class)))
                .optional().orElseThrow(CustomerService::customerNotFound);
    }
	
	AiProcessingConsent aiProcessingConsent(UUID trustedCustomerId) {
    return jdbc.sql("""
            SELECT ai_processing_consent, ai_processing_epoch
            FROM customer_preferences
            WHERE customer_id = ?
            """)
            .param(trustedCustomerId)
            .query((rs, row) -> new AiProcessingConsent(
                    rs.getBoolean("ai_processing_consent"),
                    rs.getObject("ai_processing_epoch", UUID.class)))
            .optional()
            .orElseThrow(CustomerService::customerNotFound);
}

@Transactional
AiProcessingConsent updateAiProcessingConsent(
        UUID trustedCustomerId,
        boolean enabled) {

    requireUpdated(jdbc.sql("""
            UPDATE customer_preferences
            SET ai_processing_epoch =
                    CASE
                        WHEN ai_processing_consent <> :enabled
                        THEN gen_random_uuid()
                        ELSE ai_processing_epoch
                    END,
                ai_processing_consent = :enabled
            WHERE customer_id = :customer
            """)
            .param("enabled", enabled)
            .param("customer", trustedCustomerId)
            .update());

    audit(trustedCustomerId, "AI_PROCESSING_CONSENT_UPDATED");

    return aiProcessingConsent(trustedCustomerId);
}

    @Transactional
    public Preferences updatePreferences(UUID trustedCustomerId, Preferences input) {
        requireUpdated(jdbc.sql("""
                UPDATE customer_preferences SET locale = :locale, marketing_opt_in = :marketing,
                    personalization_epoch = CASE WHEN personalization_consent <> :consent
                        THEN gen_random_uuid() ELSE personalization_epoch END,
                    personalization_consent = :consent WHERE customer_id = :customer
                """).param("locale", input.locale()).param("marketing", input.marketingOptIn())
                .param("consent", input.personalizationConsent()).param("customer", trustedCustomerId).update());
        audit(trustedCustomerId, "PREFERENCES_UPDATED");
        return input;
    }

    private void audit(UUID customerId, String eventType) {
        jdbc.sql("INSERT INTO customer_audit (customer_id, event_type) VALUES (?, ?)")
                .params(customerId, eventType).update();
    }

    private static void requireUpdated(int count) {
        if (count == 0) {
            throw customerNotFound();
        }
    }

    private static ResponseStatusException customerNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
    }
}
