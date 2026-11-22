
package com.retail.agent.context;

import java.util.Optional;
import java.util.regex.Pattern;

final class RetailIntentInputFilter {

    private static final Pattern SAFE_QUESTION = Pattern.compile(
            "^(?i:(?:show(?: me)?|display|view)\\s+"
            + "(?:(?:the|a)\\s+)?"
            + "(?:(?:details\\s+(?:of|for|about)\\s+))?"
            + "(?:(?:product|item)\\s+)?"
            + "(?:ELEC-1001|HOME-1002))[.!?]?$"
    );

    private RetailIntentInputFilter() {
    }

    static Optional<String> sanitize(String question) {

        if (question == null
                || question.isBlank()
                || question.length() > 1200) {
            return Optional.empty();
        }

        String normalized = question.strip();

        if (!SAFE_QUESTION.matcher(normalized).matches()) {
            return Optional.empty();
        }

        return Optional.of(normalized);
    }
}
