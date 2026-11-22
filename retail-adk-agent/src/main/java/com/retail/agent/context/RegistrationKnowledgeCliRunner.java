package com.retail.agent.context;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Local host for the dedicated registration ADK agent and operator-only corpus lifecycle. */
public final class RegistrationKnowledgeCliRunner {
    private RegistrationKnowledgeCliRunner() {}

    public static void main(String[] args) {
        if (args.length < 2 || !(args[0].equals("ask") && args.length == 2
                || args[0].equals("demo") && args.length == 2
                || (args[0].equals("publish") || args[0].equals("revoke")) && args.length == 3)) {
            throw new IllegalArgumentException("Use demo <question>, ask <question>, publish <topic> <expectedVersion>, or revoke <topic> <version>");
        }
        String command = args[0];
        boolean demo = command.equals("demo");
        if (!demo && !"postgres".equals(System.getenv("DOCUMENT_REPOSITORY"))) {
            throw new IllegalArgumentException("Set DOCUMENT_REPOSITORY=postgres for persistent operations");
        }
        boolean liveEmbeddings = "true".equalsIgnoreCase(System.getenv("REGISTRATION_LIVE_EMBEDDINGS"));
        if (demo && liveEmbeddings) {
            throw new IllegalArgumentException("The offline demo uses deterministic embeddings");
        }

        try (var repository = demo
                ? new DocumentRepositoryProvider.Handle(new InMemoryDocumentRepository(), List.of())
                : DocumentRepositoryProvider.open(key -> command.equals("ask")
                        && (key.startsWith("DOCUMENT_POSTGRES_INGEST_") || key.startsWith("DOCUMENT_POSTGRES_RETENTION_"))
                        ? null : System.getenv(key));
             var auditPool = demo ? null : PostgresGovernanceAudit.writerPool(System::getenv);
             var client = liveEmbeddings ? Client.builder().build() : null) {
            GovernanceAudit audit = demo ? (event, access) -> {} : new PostgresGovernanceAudit(auditPool);
            EmbeddingProvider embeddings = client == null
                    ? new FakeEmbeddingProvider(768) : new GeminiEmbeddingProvider(client, 768);
            var corpus = Path.of("docs", "registration-knowledge");
            var clock = Clock.systemUTC();
            boolean conversational = command.equals("ask")
                    && "true".equalsIgnoreCase(System.getenv("REGISTRATION_GEMINI_SYNTHESIS"));
            var agent = conversational
                    ? RegistrationKnowledgeAgent.conversational(corpus, repository.repository(), embeddings, audit, clock)
                    : new RegistrationKnowledgeAgent(corpus, repository.repository(), embeddings, audit, clock);
            switch (command) {
                case "demo" -> {
                    for (var topic : RegistrationKnowledgeAgent.Topic.values()) {
                        agent.publish(topic, 0);
                    }
                    System.out.println(ask(agent, args[1]));
                }
                case "ask" -> System.out.println(ask(agent, args[1]));
                case "publish" -> {
                    var head = agent.publish(topic(args[1]), Long.parseLong(args[2]));
                    System.out.println("Published " + head.id() + " version " + head.version());
                }
                case "revoke" -> {
                    agent.revoke(topic(args[1]), Long.parseLong(args[2]));
                    System.out.println("Revoked registration topic " + args[1]);
                }
                default -> throw new IllegalStateException("Unknown registration command");
            }
        } catch (RuntimeException failure) {
            if (!command.equals("ask")) {
                throw failure;
            }
            System.out.println(RegistrationKnowledgeAgent.UNAVAILABLE);
        }
    }

    private static RegistrationKnowledgeAgent.Topic topic(String key) {
        return RegistrationKnowledgeAgent.Topic.valueOf(key.toUpperCase(Locale.ROOT));
    }

    static String ask(RegistrationKnowledgeAgent agent, String question) {
        var runner = new InMemoryRunner(agent, "retail_registration_knowledge");
        var session = runner.sessionService().createSession(runner.appName(), "anonymous", Map.of(),
                UUID.randomUUID().toString()).blockingGet();
        try {
            return runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(question)),
                            RunConfig.builder().build())
                    .filter(Event::finalResponse).map(Event::stringifyContent).lastOrError().blockingGet();
        } finally {
            runner.sessionService().deleteSession(runner.appName(), session.userId(), session.id()).blockingAwait();
        }
    }
}
