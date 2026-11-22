package com.retail.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.genai.Client;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

public final class ProductKnowledgeCliRunner {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new SimpleModule()
            .addSerializer(Instant.class, ToStringSerializer.instance));
    private ProductKnowledgeCliRunner() {}

    public static void main(String[] args) throws Exception {
        boolean live = args.length == 2 && args[0].equals("ask-live");
        boolean ask = live || args.length == 2 && args[0].equals("ask");
        boolean operator = args.length == 3 && (args[0].equals("publish") || args[0].equals("revoke"));
        if (!ask && !operator) {
            throw new IllegalArgumentException("Use ask or ask-live <question>, publish <sku> <expectedVersion>, or revoke <sku> <version>");
        }
        boolean liveEmbeddings = "true".equalsIgnoreCase(System.getenv("PRODUCT_LIVE_EMBEDDINGS"));
        try (var documents = DocumentRepositoryProvider.open(key -> {
                 if (key.equals("DOCUMENT_REPOSITORY")) {
                     return "postgres";
                 }
                 if (key.startsWith("DOCUMENT_POSTGRES_RETENTION_") || ask && key.startsWith("DOCUMENT_POSTGRES_INGEST_")) {
                     return null;
                 }
                 return System.getenv(key);
             });
             var audit = PostgresGovernanceAudit.writerPool(System::getenv);
             var client = liveEmbeddings && !args[0].equals("revoke") ? Client.builder().build() : null) {
            var embedding = client == null ? new FakeEmbeddingProvider(768) : new GeminiEmbeddingProvider(client, 768);
            var governanceAudit = new PostgresGovernanceAudit(audit);
            var clock = Clock.systemUTC();
            var agent = new ProductKnowledgeAgent(new PostgresProductCatalog(documents.pools().get(0)),
                    Path.of("docs", "product-knowledge"), documents.repository(), embedding,
                    governanceAudit, clock);
            if (ask) {
                var result = live ? askLive(agent, args[1], governanceAudit, clock) : agent.answer(args[1]);
                System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                return;
            }
            String sku = args[1].strip().toUpperCase(Locale.ROOT);
            long version = Long.parseLong(args[2]);
            if (args[0].equals("publish")) {
                agent.publish(sku, version);
            } else {
                agent.revoke(sku, version);
            }
            System.out.println("Completed " + args[0] + " for " + sku);
        } catch (RuntimeException failure) {
            if (!ask) {
                throw failure;
            }
            var unavailable = new ProductKnowledgeResponse(ProductKnowledgeResponse.Outcome.UNAVAILABLE,
                    java.util.List.of(), "Product knowledge is temporarily unavailable.", java.util.Map.of());
            System.out.println(JSON.writeValueAsString(unavailable));
        }
    }

    private static ProductKnowledgeResponse askLive(ProductKnowledgeAgent agent, String question,
                                                     GovernanceAudit audit, Clock clock) {
        try (var host = ProductInventoryHost.fromEnvironment(audit, clock)) {
            return host.answer(agent, question);
        } catch (RuntimeException configurationFailure) {
            return agent.answer(question);
        }
    }
}
