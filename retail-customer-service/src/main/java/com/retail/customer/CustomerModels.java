package com.retail.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.UUID;

final class CustomerModels {
    private CustomerModels() {}

    record Registration(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 80) String givenName,
            @NotBlank @Size(max = 80) String familyName) {
        Registration {
            if (email != null) {
                email = email.strip().toLowerCase(Locale.ROOT);
            }
            givenName = strip(givenName);
            familyName = strip(familyName);
        }
    }

    record ProfilePatch(
            @Size(min = 1, max = 80) String givenName,
            @Size(min = 1, max = 80) String familyName) {
        ProfilePatch {
            givenName = strip(givenName);
            familyName = strip(familyName);
        }
    }

    record Profile(UUID customerId, String email, String givenName, String familyName) {}

    record Preferences(
            @NotNull @Pattern(regexp = "[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*")
            @Size(max = 35) String locale,
            @NotNull Boolean marketingOptIn,
            @NotNull Boolean personalizationConsent) {}

    record DemoSelection(@NotNull UUID customerId) {}

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
