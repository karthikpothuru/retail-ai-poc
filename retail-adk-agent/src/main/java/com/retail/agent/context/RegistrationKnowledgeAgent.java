package com.retail.agent.context;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.retail.agent.context.MemoryTypes.Origin.RETRIEVED_EVIDENCE;
import static com.retail.agent.context.MemoryTypes.Origin.USER_INPUT;
import static com.retail.agent.context.MemoryTypes.Sensitivity.PUBLIC;
import static com.retail.agent.context.TrustedCallerBoundary.Operation.DOCUMENT_INGEST;
import static com.retail.agent.context.TrustedCallerBoundary.Operation.DOCUMENT_MANAGE;
import static com.retail.agent.context.TrustedCallerBoundary.Operation.DOCUMENT_READ;

/** Public, read-only ADK entry point for reviewed registration instructions. */
public final class RegistrationKnowledgeAgent extends BaseAgent {
    static final String TENANT = "public-registration-knowledge";
    static final String READER = "anonymous-registration-reader";
    static final String PUBLISHER = "registration-knowledge-publisher";
    static final String UNAVAILABLE = "Registration knowledge is temporarily unavailable.";
    private static final String QUESTION_SOURCE = "registration-question";
    private static final String MIME = "text/plain";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+");
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b");

    enum Topic {
        REGISTRATION("registration", "Register a synthetic customer",
                "80cb3573ae7e8be4bab700805ea48bd36668ca9c097d14250ee8eb29bb897766",
                List.of("how do i register", "how do i register a customer", "how do i register a synthetic customer", "how do i create a customer account", "customer registration steps")),
        ACCOUNT("account", "Account information and local access",
                "96de4dc1b1ec807cc1e2122794921e391d503e7715671316138f07693ca64dbb",
                List.of("what account information is stored", "what customer information is stored", "how do i view my profile", "how do i select a demo customer", "does registration require email verification", "do i need oidc to register", "is email verification required")),
        CONSENT("consent", "Consent and preferences",
                "35f2bcd32cfec346fbf9805b48127abb0f225cf2ac9de09887eb6a8c39f02a9a",
                List.of("what are the default preferences", "is personalization consent enabled by default", "what is the default personalization consent", "how do i withdraw consent", "how do i withdraw personalization consent", "how do i update my preferences", "how do i change consent")),
        PROFILE("profile", "Update a demo customer profile",
                "a2a63e8f092a219e5f5c4207c6da58564c7422e969874fee16cc72bc0d3d63fe",
                List.of("how do i update my profile", "how do i update a customer profile", "how do i change my name", "can i change my email")),
        FAQ("faq", "Registration errors and FAQs",
                "529e4c94de58badd73798d232415de0aa00a15772e0641bd7b77d9cf0d99656a",
                List.of("what if my email is already registered", "what happens with a duplicate email", "why did registration fail", "what does registration error 409 mean", "what are common registration errors"));

        final String key;
        final String title;
        final String reviewedHash;
        final List<String> questions;

        Topic(String key, String title, String reviewedHash, List<String> questions) {
            this.key = key;
            this.title = title;
            this.reviewedHash = reviewedHash;
            this.questions = questions;
        }

        String sourceId() {
            return "phase81-registration-" + key;
        }

        UUID documentId() {
            return UUID.nameUUIDFromBytes((TENANT + "/" + sourceId()).getBytes(StandardCharsets.UTF_8));
        }

        Path file() {
            return Path.of(key + ".txt");
        }

        boolean accepts(EnterpriseDocument.Evidence evidence) {
            var citation = evidence.citation();
            return evidence.classification() == PUBLIC && citation.documentId().equals(documentId())
                    && citation.sourceId().equals(sourceId()) && reviewed(evidence.text());
        }

        boolean reviewed(String text) {
            return reviewedHash.equals(EnterpriseDocument.hash(text));
        }
    }

    private final Clock clock;
    private final TrustedCallerBoundary.Deployment deployment;
    private final DocumentExtractor extractor;
    private final GovernedDocumentService documents;
    private final DocumentGrounding grounding;
    private final ContextAssemblyService assembler;
    private final RegistrationSynthesis synthesis;

    public RegistrationKnowledgeAgent(Path corpus, DocumentRepository repository, EmbeddingProvider embeddings,
                                      GovernanceAudit audit, Clock clock) {
        this(corpus, repository, embeddings, audit, clock, null);
    }

    public static RegistrationKnowledgeAgent conversational(Path corpus, DocumentRepository repository,
            EmbeddingProvider embeddings, GovernanceAudit audit, Clock clock) {
        return new RegistrationKnowledgeAgent(corpus, repository, embeddings, audit, clock,
                new RegistrationSynthesis(null, RegistrationSynthesis.DEFAULT_BUDGET));
    }

    RegistrationKnowledgeAgent(Path corpus, DocumentRepository repository, EmbeddingProvider embeddings,
                               GovernanceAudit audit, Clock clock, RegistrationSynthesis synthesis) {
        super("registration_knowledge", "Public Phase 8.1 registration information", List.of(), List.of(), List.of());
        this.clock = clock;
        this.synthesis = synthesis;
        this.deployment = TrustedCallerBoundary.bootstrap(clock);
        var entries = new HashMap<String, SourceRegistry.Source>();
        entries.put(QUESTION_SOURCE, new SourceRegistry.Source(QUESTION_SOURCE, TENANT, READER,
                USER_INPUT, "Public registration question"));
        for (Topic topic : Topic.values()) {
            entries.put(topic.sourceId(), new SourceRegistry.Source(topic.sourceId(), TENANT, null,
                    RETRIEVED_EVIDENCE, "Phase 8.1 POC: " + topic.title));
        }
        var sources = new SourceRegistry(entries);
        extractor = new DocumentExtractor(corpus, 10000, 4000);
        documents = new GovernedDocumentService(deployment.boundary(), repository, sources, extractor,
                new DocumentChunker(1024, 64), embeddings, audit, clock);
        grounding = new DocumentGrounding(documents, 10, 0.05, 12000);
        var contextAudit = new ContextAuditLog(clock);
        var emptyMemory = new InMemoryMemoryRepository(clock, new MemoryWritePolicy(sources, clock), contextAudit);
        assembler = new ContextAssemblyService(emptyMemory, sources, new ApprovedInstructionCatalog(Map.of(), sources),
                contextAudit, clock, ContextAssemblyService.TokenEstimator.utf8Bytes());
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {
        return Flowable.fromCallable(() -> {
            // A single text question is the complete public input. ADK user/session labels are transport metadata.
            var parts = context.userContent().flatMap(Content::parts).orElse(List.of());
            String question = parts.size() == 1 ? parts.get(0).text().orElse("") : "";
            return Event.builder().author(name()).invocationId(context.invocationId())
                    .content(Content.fromParts(Part.fromText(answer(question))))
                    .actions(new EventActions()).build();
        });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
        return Flowable.error(new UnsupportedOperationException("Use the ADK text runner for registration knowledge."));
    }

    String answer(String question) {
        return answer(question, ignored -> {});
    }

    String answer(String question, java.util.function.Consumer<Runnable> evidence) {
        var topic = resolve(question);
        if (topic.isEmpty() && (synthesis == null || question.isBlank() || question.length() > 1200)) {
            return DocumentGrounding.insufficient();
        }
        var credential = issue(READER, DOCUMENT_READ);
        try {
            var caller = deployment.boundary().authenticate(credential.token(), null, null);
            if (topic.isPresent()) {
                return groundedAnswer(caller, topic.get(), evidence);
            }
            return conversationalAnswer(caller, sanitize(question), evidence);
        } catch (SecurityException | ContextAssemblyService.BudgetExceededException rejected) {
            return DocumentGrounding.insufficient();
        } catch (RuntimeException unavailable) {
            return UNAVAILABLE;
        } finally {
            deployment.provisioner().revoke(credential.authenticationId());
        }
    }

    private String conversationalAnswer(TrustedCallerBoundary.Caller caller, String question,
                                        java.util.function.Consumer<Runnable> evidence) {
        var prepared = grounding.prepare(caller, question, 10);
        // A current candidate from outside the reviewed corpus is a conflict. Do not expose it to Gemini.
        if (prepared.evidence().stream().anyMatch(e -> Arrays.stream(Topic.values()).noneMatch(t -> t.accepts(e)))) {
            return DocumentGrounding.insufficient();
        }
        var goal = new ContextAssemblyService.Data("registration-question", question, QUESTION_SOURCE, PUBLIC);
        var request = new ContextAssemblyService.Request(goal, List.of(), prepared.data(), List.of(),
                List.of(), List.of(), synthesis.budget(), 0);
        var context = assembler.assemble(deployment.boundary().require(caller, DOCUMENT_READ), request);
        var included = DocumentGrounding.included(prepared, context);
        if (included.isEmpty()) {
            return RegistrationSynthesis.ACCOUNT_BOUNDARY + "\n\n" + DocumentGrounding.insufficient();
        }

        String proposal = synthesis.propose(context, () -> grounding.revalidate(caller, prepared, context));
        var answer = RegistrationSynthesis.verifyAndRender(proposal, included);
        if (answer.isEmpty()) {
            grounding.revalidate(caller, prepared, context);
            return RegistrationSynthesis.ACCOUNT_BOUNDARY + "\n\n" + DocumentGrounding.insufficient();
        }
        grounding.validateAnswer(caller, prepared, context, answer.get());
        evidence.accept(() -> revalidate(prepared, context));
        return answer.get();
    }

    static String sanitize(String question) {
        return UUID_TEXT.matcher(EMAIL.matcher(question.strip()).replaceAll("[email removed]"))
                .replaceAll("[customer ID removed]");
    }

    private String groundedAnswer(TrustedCallerBoundary.Caller caller, Topic topic,
                                  java.util.function.Consumer<Runnable> checks) {
        // Embed a fixed public topic, keeping anonymous input and any customer data out of the provider.
        var prepared = grounding.prepare(caller, topic.title, 10);
        var evidence = prepared.evidence().stream().filter(topic::accepts).findFirst();
        if (evidence.isEmpty()) {
            return DocumentGrounding.insufficient();
        }
        var selected = evidence.orElseThrow();
        var data = prepared.data().stream()
                .filter(block -> block.id().equals("doc-" + selected.citation().chunkId())).toList();
        var narrowed = new DocumentGrounding.Prepared(prepared.retrieval(), data, List.of(selected));
        var goal = new ContextAssemblyService.Data("registration-faq", topic.title, QUESTION_SOURCE, PUBLIC);
        var request = new ContextAssemblyService.Request(goal, List.of(), data, List.of(), List.of(), List.of(),
                8000, 0);
        var context = assembler.assemble(deployment.boundary().require(caller, DOCUMENT_READ), request);
        if (DocumentGrounding.included(narrowed, context).isEmpty()) {
            return DocumentGrounding.insufficient();
        }
        String answer = selected.text() + "\n\nSource: " + topic.title + " " + selected.citation().marker();
        grounding.validateAnswer(caller, narrowed, context, answer);
        checks.accept(() -> revalidate(narrowed, context));
        return answer;
    }

    private void revalidate(DocumentGrounding.Prepared prepared, AssembledContext context) {
        var credential = issue(READER, DOCUMENT_READ);
        try {
            var reader = deployment.boundary().authenticate(credential.token(), null, null);
            grounding.revalidate(reader, prepared, context);
        } finally {
            deployment.provisioner().revoke(credential.authenticationId());
        }
    }

    static boolean isReviewedQuestion(String question) {
        return resolve(question).isPresent();
    }

    // Host-only corpus operations. Neither is registered as an ADK tool or exposed to anonymous callers.
    EnterpriseDocument.Head publish(Topic topic, long expectedVersion) {
        var extracted = extractor.extract(topic.file(), MIME);
        if (extracted.segments().size() != 1 || !topic.reviewed(extracted.segments().get(0).text())) {
            throw new SecurityException("Registration source differs from the reviewed corpus");
        }
        var credential = issue(PUBLISHER, DOCUMENT_INGEST);
        try {
            var caller = deployment.boundary().authenticate(credential.token(), null, null);
            var draft = new EnterpriseDocument.Draft(topic.documentId(), expectedVersion, topic.sourceId(),
                    "phase81-registration-poc", MIME, PUBLIC, Set.of(READER), clock.instant(), null);
            return documents.ingest(caller, draft, topic.file());
        } finally {
            deployment.provisioner().revoke(credential.authenticationId());
        }
    }

    void revoke(Topic topic, long version) {
        var credential = issue(PUBLISHER, DOCUMENT_MANAGE);
        try {
            var caller = deployment.boundary().authenticate(credential.token(), null, null);
            documents.revoke(caller, topic.documentId(), version);
        } finally {
            deployment.provisioner().revoke(credential.authenticationId());
        }
    }

    private TrustedCallerBoundary.IssuedCredential issue(String principal, TrustedCallerBoundary.Operation operation) {
        var identity = new ContextIdentity(TENANT, principal, Set.of(), Set.of(), Set.of(), PUBLIC);
        return deployment.provisioner().issue(identity, Set.of(operation), PUBLIC, clock.instant().plusSeconds(120));
    }

    private static Optional<Topic> resolve(String question) {
        if (question.length() > 240) {
            return Optional.empty();
        }
        String normalized = question.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .replaceFirst("\\?$", "").stripTrailing();
        return Arrays.stream(Topic.values()).filter(topic -> topic.questions.contains(normalized)).findFirst();
    }
}
