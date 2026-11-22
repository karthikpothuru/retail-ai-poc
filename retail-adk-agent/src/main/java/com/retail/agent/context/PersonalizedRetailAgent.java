package com.retail.agent.context;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.retail.agent.context.ProductKnowledgeResponse.Outcome.*;

/** A fresh ADK turn with explicit host bindings. The prompt cannot create or select these bindings. */
public final class PersonalizedRetailAgent extends BaseAgent {
    private static final Pattern REGISTRATION = Pattern.compile(
            "\\b(regist(?:er|ering|ration)|consent|profile|oidc|email verification|duplicate email|sign up|customer account)\\b"
                    + "|\\b(?:open|create) (?:an? )?account\\b");
    private static final Pattern PRODUCT = Pattern.compile(
            "\\b(product|products|catalog|keyboard|mouse|monitor|display|headset|battery|usb|hdmi)\\b");
    private static final Pattern CLAUSES = Pattern.compile(
            "(?<=\\?)\\s+|\\s*;\\s*|,?\\s+and\\s+(?=(?:how|what|which|can|compare|check|tell|show|is|are|do|does|find)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final int INPUT_LIMIT = 1200;
    private static final Pattern DOC_MARKER = Pattern.compile("\\[DOC:[a-f0-9-]{36}:v[1-9][0-9]*:[a-f0-9-]{36}\\]");

    public record Citation(String marker, String channel) {}
    
public record Result(
        String answer,
        Optional<String> registration,
        Optional<ProductKnowledgeResponse> product,
        boolean personalized,
        List<Citation> citations,
        boolean capabilitiesResponse) {

    public Result {
        citations = List.copyOf(citations);
    }

    public Result(
            String answer,
            Optional<String> registration,
            Optional<ProductKnowledgeResponse> product,
            boolean personalized) {
        this(answer, registration, product, personalized,
                List.of(), false);
    }

    public Result(
            String answer,
            Optional<String> registration,
            Optional<ProductKnowledgeResponse> product,
            boolean personalized,
            List<Citation> citations) {
        this(answer, registration, product, personalized,
                citations, false);
    }
}


    private record Route(String registration, String product) {
        boolean canPersonalize() {
            return !product.isEmpty() && (!ProductInventoryLookup.requested(product)
                    || ProductInventoryLookup.wantsDetails(product));
        }
    }

    private final RegistrationKnowledgeAgent registration;
    private final ProductKnowledgeAgent products;
    private final ConsentAwareCustomerMemory memory;
    private final DemoCustomerContextClient.SessionCookie demoSession;
    private final ProductInventoryHost inventory;

    public PersonalizedRetailAgent(RegistrationKnowledgeAgent registration, ProductKnowledgeAgent products,
                                    ConsentAwareCustomerMemory memory, DemoCustomerContextClient.SessionCookie demoSession) {
        this(registration, products, memory, demoSession, null);
    }

    PersonalizedRetailAgent(RegistrationKnowledgeAgent registration, ProductKnowledgeAgent products,
                            ConsentAwareCustomerMemory memory, DemoCustomerContextClient.SessionCookie demoSession,
                            ProductInventoryHost inventory) {
        super("personalized_retail", "Single-turn governed retail information", List.of(), List.of(), List.of());
        this.registration = Objects.requireNonNull(registration);
        this.products = Objects.requireNonNull(products);
        this.memory = memory;
        this.demoSession = demoSession;
        this.inventory = inventory;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {
        return Flowable.fromCallable(() -> {
            var parts = context.userContent().flatMap(Content::parts).orElse(List.of());
            String question = parts.size() == 1 ? parts.get(0).text().orElse("") : "";
            return Event.builder().author(name()).invocationId(context.invocationId())
                    .content(Content.fromParts(Part.fromText(answer(question).answer())))
                    .actions(new EventActions()).build();
        });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
        return Flowable.error(new UnsupportedOperationException("Use the single-turn ADK text runner."));
    }

    public Result answer(String question) {
        if (question == null || question.isBlank() || question.length() > INPUT_LIMIT) {
            return new Result("Ask one retail question of up to 1,200 characters.", Optional.empty(), Optional.empty(), false, List.of());
        }
        // Remove the bound secret before any routing or delegation even if copied into the question.
        String publicQuestion = demoSession == null ? question : question.replace(demoSession.value(), "[session removed]");
        var route = route(RegistrationKnowledgeAgent.sanitize(publicQuestion));
		
String normalized = publicQuestion
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();

boolean capabilitiesQuestion =
        normalized.contains("what can you help me with")
        || normalized.contains("what can you do")
        || normalized.contains("how can you help me")
        || normalized.contains("your capabilities");

if (capabilitiesQuestion) {
    String capabilities = """
            I am a retail shopping assistant.

            I can help you with:
            - Finding products in the available catalog.
            - Explaining product details supported by reviewed documentation.
            - Answering registration and account questions when authorized documentation is available.
            - Checking inventory through connected inventory services when available.

            Product specifications and inventory availability require trusted evidence.
            """;

    return new Result(
            capabilities,
            Optional.empty(),
            Optional.empty(),
            false,
            List.of(),true);
}

        if (route.registration().isEmpty() && route.product().isEmpty()) {
            return new Result("Ask a registration, product, or inventory question.", Optional.empty(), Optional.empty(), false, List.of());
        }
        var preference = route.canPersonalize() && memory != null && demoSession != null
                ? memory.personalization(demoSession) : Optional.<ConsentAwareCustomerMemory.Personalization>empty();

        var registrationChecks = new ArrayList<Runnable>();
        var productChecks = new ArrayList<Runnable>();
        String registrationAnswer = route.registration().isEmpty() ? null
                : registration.answer(route.registration(), registrationChecks::add);
        var productAnswer = route.product().isEmpty() ? null : inventory == null
                ? products.answer(route.product(), productChecks::add)
                : inventory.answer(products, route.product(), productChecks::add);

        if (!current(registrationChecks)) {
            registrationAnswer = DocumentGrounding.insufficient();
        }
        if (productAnswer != null) {
            productAnswer = revalidateProduct(productAnswer, productChecks);
        }

        boolean personalize = preference.isPresent() && productAnswer != null && productAnswer.outcome() == FOUND
                && !productAnswer.products().isEmpty() && memory.stillCurrent(demoSession, preference.get());
        var sections = new ArrayList<String>();
        var citations = new ArrayList<Citation>();
        if (registrationAnswer != null) {
            sections.add("Registration\n" + registrationAnswer);
            if (!registrationChecks.isEmpty()) addCitations(citations, registrationAnswer, "REGISTRATION");
        }
        if (productAnswer != null) {
            String catalog = personalize ? formatCatalog(productAnswer.products(), preference.get().level()) + "\n\n" : "";
            sections.add(catalog + productAnswer.answer());
            if (!productChecks.isEmpty()) addCitations(citations, productAnswer.answer(), "PRODUCT");
        }
        return new Result(String.join("\n\n", sections), Optional.ofNullable(registrationAnswer),
                Optional.ofNullable(productAnswer), personalize, citations);
    }

    private static void addCitations(List<Citation> citations, String checkedText, String channel) {
        DOC_MARKER.matcher(checkedText).results().map(match -> new Citation(match.group(), channel))
                .distinct().forEach(citations::add);
    }

    private ProductKnowledgeResponse revalidateProduct(ProductKnowledgeResponse answer, List<Runnable> checks) {
        if (!current(List.of(() -> products.revalidateCatalog(answer.products())))) {
            return new ProductKnowledgeResponse(INSUFFICIENT, List.of(), "Product listing changed. Please retry.", Map.of());
        }
        if (current(checks)) {
            return answer;
        }
        var observations = answer.liveInventoryBySku();
        var status = observations.values().stream().anyMatch(o ->
                o.status() == ProductKnowledgeResponse.InventoryStatus.REPORTED) ? FOUND : INSUFFICIENT;
        String text = DocumentGrounding.insufficient();
        if (!observations.isEmpty()) {
            text += "\n\nInventory\n" + answer.products().stream().map(p -> observations.get(p.sku()))
                    .filter(Objects::nonNull).map(ProductInventoryLookup::render).collect(Collectors.joining("\n"));
        }
        return new ProductKnowledgeResponse(status, answer.products(), text, observations);
    }

    private static boolean current(List<Runnable> checks) {
        try {
            checks.forEach(Runnable::run);
            return true;
        } catch (RuntimeException stale) {
            return false;
        }
    }

    private static String formatCatalog(List<ProductCatalog.Product> entries, ConsentAwareCustomerMemory.DetailLevel level) {
        return switch (level) {
            case CONCISE -> "Products: " + entries.stream().map(p -> field(p.name()) + " (" + p.sku() + ")")
                    .collect(Collectors.joining(", "));
            case DETAILED -> "Catalog details\n" + entries.stream().map(p ->
                    "- " + p.sku() + " | " + field(p.name()) + " | " + field(p.category()))
                    .collect(Collectors.joining("\n"));
        };
    }

    private static String field(String value) {
        return value.replaceAll("[\\p{Cntrl}\\[\\]]", " ").strip();
    }

    private static Route route(String question) {
        var registration = new ArrayList<String>();
        var product = new ArrayList<String>();
        for (String clause : CLAUSES.split(question)) {
            if (clause.isBlank()) {
                continue;
            }
            String lower = clause.toLowerCase(Locale.ROOT);
            boolean registrationQuestion = RegistrationKnowledgeAgent.isReviewedQuestion(clause)
                    || REGISTRATION.matcher(lower).find();
            if (registrationQuestion) {
                registration.add(clause);
            }
            if (!registrationQuestion || ProductKnowledgeAgent.mentionsExplicitSku(clause)
                    || ProductInventoryLookup.requested(clause) || PRODUCT.matcher(lower).find()) {
                product.add(clause);
            }
        }
        return new Route(String.join(" ", registration), String.join(" ", product));
    }
}
