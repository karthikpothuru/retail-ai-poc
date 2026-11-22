package com.retail.agent.context;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

public final class PersonalizedRetailCliRunner {
    private PersonalizedRetailCliRunner() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass one retail question");
        }
        try {
            String answer = withAgent(sessionCookie(), agent -> run(agent, args[0]));
            System.out.println(answer);
        } catch (RuntimeException unavailable) {
            System.out.println("The retail service is temporarily unavailable.");
        }
    }

  
        
static <T> T withAgent(
        DemoCustomerContextClient.SessionCookie cookie,
        java.util.function.Function<PersonalizedRetailAgent, T> operation) {

    boolean registrationEmbeddings =
            enabled("REGISTRATION_LIVE_EMBEDDINGS");
    boolean productEmbeddings =
            enabled("PRODUCT_LIVE_EMBEDDINGS");

    var clock = Clock.systemUTC();

    String repositoryMode = System.getenv()
            .getOrDefault("DOCUMENT_REPOSITORY", "postgres");

    if (!repositoryMode.equals("postgres")
            && !repositoryMode.equals("in-memory")) {
        throw new IllegalArgumentException(
                "Unsupported DOCUMENT_REPOSITORY: " + repositoryMode);
    }

    boolean localMode = repositoryMode.equals("in-memory");

    if (localMode && !enabled("RETAIL_LOCAL_POC_MODE")) {
        throw new IllegalStateException(
                "In-memory repositories require RETAIL_LOCAL_POC_MODE=true");
    }

    try (var documents = DocumentRepositoryProvider.open(
                key -> {
                    if (key.equals("DOCUMENT_REPOSITORY")) {
                        return repositoryMode;
                    }
                    if (key.startsWith("DOCUMENT_POSTGRES_INGEST_")
                            || key.startsWith("DOCUMENT_POSTGRES_RETENTION_")) {
                        return null;
                    }
                    return System.getenv(key);
                });
         var auditPool = localMode
                 ? null
                 : PostgresGovernanceAudit.writerPool(System::getenv);
         var client = registrationEmbeddings || productEmbeddings
                 ? Client.builder().build()
                 : null;
         var memoryStore = openMemory(cookie, clock)) {

        GovernanceAudit audit = localMode
                ? new InMemoryGovernanceAudit()
                : new PostgresGovernanceAudit(auditPool);

        ProductCatalog catalog = localMode
                ? InMemoryProductCatalog.demo()
                : new PostgresProductCatalog(documents.pools().get(0));

        var registration = RegistrationKnowledgeAgent.conversational(
                Path.of("docs", "registration-knowledge"),
                documents.repository(),
                embeddings(registrationEmbeddings, client),
                audit,
                clock);

        var product = new ProductKnowledgeAgent(
                catalog,
                Path.of("docs", "product-knowledge"),
                documents.repository(),
                embeddings(productEmbeddings, client),
                audit,
                clock);

        var memory = customerMemory(memoryStore, audit, clock);

        try (var inventory = openInventory(audit, clock)) {
            var agent = new PersonalizedRetailAgent(
                    registration, product, memory, cookie, inventory);
            return operation.apply(agent);
        }
    }
}

    

    static String run(PersonalizedRetailAgent agent, String question) {
        var runner = new InMemoryRunner(agent, "retail_single_turn");
        var session = runner.sessionService().createSession(runner.appName(), "local-retail-turn", Map.of(),
                UUID.randomUUID().toString()).blockingGet();
        try {
            return runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(question)),
                            RunConfig.builder().build())
                    .filter(Event::finalResponse).map(Event::stringifyContent).singleOrError().blockingGet();
        } finally {
            runner.sessionService().deleteSession(runner.appName(), session.userId(), session.id()).blockingAwait();
        }
    }

    private static DemoCustomerContextClient.SessionCookie sessionCookie() {
        String value = System.getenv("RETAIL_DEMO_SESSION");
        try {
            return value == null ? null : new DemoCustomerContextClient.SessionCookie(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static MemoryRepositoryProvider.Handle openMemory(DemoCustomerContextClient.SessionCookie cookie, Clock clock) {
        if (cookie == null || !"postgres".equals(System.getenv("MEMORY_REPOSITORY"))) {
            return null;
        }
        try {
            return MemoryRepositoryProvider.open(key -> key.equals("MEMORY_POSTGRES_MIGRATE") ? null : System.getenv(key),
                    clock, ConsentAwareCustomerMemory.writePolicy(clock), new ContextAuditLog(clock));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static ConsentAwareCustomerMemory customerMemory(MemoryRepositoryProvider.Handle repository,
                                                             GovernanceAudit audit, Clock clock) {
        if (repository == null) {
            return null;
        }
        try {
            var origin = System.getenv().getOrDefault("RETAIL_CUSTOMER_CONTEXT_URL", "http://127.0.0.1:8081");
            return new ConsentAwareCustomerMemory(new DemoCustomerContextClient(URI.create(origin)), repository.repository(),
                    audit, clock);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static ProductInventoryHost openInventory(GovernanceAudit audit, Clock clock) {
        if (System.getenv("MCP_SERVER_URL") == null) {
            return null;
        }
        try {
            return ProductInventoryHost.fromEnvironment(audit, clock);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean enabled(String flag) {
        return "true".equalsIgnoreCase(System.getenv(flag));
    }

    private static EmbeddingProvider embeddings(boolean live, Client client) {
        return live ? new GeminiEmbeddingProvider(client, 768) : new FakeEmbeddingProvider(768);
    }
}
