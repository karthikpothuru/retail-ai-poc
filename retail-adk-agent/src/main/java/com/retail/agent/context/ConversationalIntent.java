package com.retail.agent.context;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

enum ConversationalIntent {
    GREETING("Hello! Welcome to the Retail Assistant. How can I help you today?"),
    MORNING("Good morning! How can I help you today?"),
    AFTERNOON("Good afternoon! How can I help you today?"),
    EVENING("Good evening! How can I help you today?"),
    THANKS("You're welcome! Let me know if you have another question."),
    HELP("I can help you find products in the catalog, explain documented product features, "
            + "and answer setup and registration questions using authorized documentation. "
            + "I can also check inventory when the connected inventory service is available. "
            + "What would you like help with?");

    private static final Pattern SURROUNDING = Pattern.compile(
            "^[\\s\\p{P}]+|[\\s\\p{P}]+$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final String reply;

    ConversationalIntent(String reply) {
        this.reply = reply;
    }

    PersonalizedRetailAgent.Result result() {
        return new PersonalizedRetailAgent.Result(reply, Optional.empty(), Optional.empty(),
                false, List.of(), this == HELP);
    }

    static Optional<ConversationalIntent> match(String message) {
        String phrase = WHITESPACE.matcher(SURROUNDING.matcher(message).replaceAll(""))
                .replaceAll(" ").toLowerCase(Locale.ROOT);
        return Optional.ofNullable(switch (phrase) {
            case "hi", "hello", "hey" -> GREETING;
            case "good morning" -> MORNING;
            case "good afternoon" -> AFTERNOON;
            case "good evening" -> EVENING;
            case "thank you", "thanks" -> THANKS;
            case "help", "what can you do", "what can you help me with", "how can you help me",
                    "your capabilities" -> HELP;
            default -> null;
        });
    }
}
