package com.retail.agent.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Treats Gemini output as a proposed selection of evidence, never as verified prose. */
final class EvidenceSynthesis {
    static final int DEFAULT_BUDGET = 12000;
    private static final int MAX_PASSAGES = 10;
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String agentName;
    private final String instructions;

    private final BaseLlm scriptedModel;
    private final int budget;

    EvidenceSynthesis(BaseLlm scriptedModel, int budget, String agentName, String instructions) {
        this.agentName = agentName;
        this.instructions = instructions;
        this.scriptedModel = scriptedModel;
        this.budget = budget;
    }

    int budget() {
        return budget;
    }

    String propose(AssembledContext context, Runnable revalidate) {
        var estimator = ContextAssemblyService.TokenEstimator.utf8Bytes();
        var builder = LlmAgent.builder().name(agentName + "_evidence_synthesis")
                .instruction(instructions)
                .generateContentConfig(GenerateContentConfig.builder().temperature(0f).maxOutputTokens(3072)
                        .responseMimeType("application/json").build())
                .beforeModelCallbackSync((callback, request) -> {
                    revalidate.run();
                    var modelRequest = request.build();
                    int cost = estimator.estimate(modelRequest.config().map(JsonSerializable::toJson).orElse("")
                            + JsonSerializable.toJsonString(modelRequest.contents()));
                    if (cost > budget) {
                        throw new ContextAssemblyService.BudgetExceededException();
                    }
                    return Optional.empty();
                });
        if (scriptedModel == null) {
            builder.model(RetailInventoryAgent.MODEL_NAME);
        } else {
            builder.model(scriptedModel);
        }

        var runner = new InMemoryRunner(builder.build(), agentName + "_synthesis");
        var session = runner.sessionService().createSession(runner.appName(), "public-" + agentName, Map.of(),
                UUID.randomUUID().toString()).blockingGet();
        try {
            return runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(context.render())),
                            RunConfig.builder().maxLlmCalls(1).build())
                    .timeout(45, TimeUnit.SECONDS)
                    .filter(Event::finalResponse)
                    .map(Event::stringifyContent)
                    .singleOrError().blockingGet();
        } finally {
            runner.sessionService().deleteSession(runner.appName(), session.userId(), session.id()).blockingAwait();
        }
    }

    record Passage(EnterpriseDocument.Evidence evidence, String quote) {
        String render() {
            return "- " + quote + " " + evidence.citation().marker();
        }
    }

    static List<Passage> verify(String proposal, List<EnterpriseDocument.Evidence> included) {
        String response = proposal.strip();
        if (response.length() > 12000) {
            throw new SecurityException("Oversized evidence synthesis");
        }
        var allowed = included.stream().collect(Collectors.toMap(
                item -> item.citation().marker(), Function.identity()));
        try {
            var passages = JSON.readTree(response);
            if (passages == null || !passages.isArray() || passages.size() > MAX_PASSAGES) {
                throw new SecurityException("Invalid evidence synthesis structure");
            }
            if (passages.isEmpty()) {
                return List.of();
            }
            var seen = new HashSet<String>();
            var output = new ArrayList<Passage>();
            for (var item : passages) {
                var reference = item.path("citation");
                var quote = item.path("quote");
                if (!item.isObject() || item.size() != 2 || !reference.isTextual() || !quote.isTextual()) {
                    throw new SecurityException("Invalid evidence passage");
                }
                var evidence = allowed.get(reference.textValue());
                if (evidence == null || !completePassage(evidence.text(), quote.textValue())
                        || !seen.add(reference.textValue() + "\n" + quote.textValue())) {
                    throw new SecurityException("Proposed passage exceeds its cited evidence");
                }
                output.add(new Passage(evidence, quote.textValue()));
            }
            return List.copyOf(output);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new SecurityException("Malformed evidence synthesis");
        }
    }

    private static boolean completePassage(String evidence, String quote) {
        if (quote.isBlank() || quote.length() > evidence.length()) {
            return false;
        }
        // Check exact sentence boundaries without changing any bytes of the reviewed excerpt.
        var ends = SENTENCE_BREAK.matcher(evidence).results().toList();
        for (int start = evidence.indexOf(quote); start >= 0; start = evidence.indexOf(quote, start + 1)) {
            int from = start;
            int to = start + quote.length();
            if ((from == 0 || ends.stream().anyMatch(match -> match.end() == from))
                    && (to == evidence.length() || ends.stream().anyMatch(match -> match.start() == to))) {
                return true;
            }
        }
        return false;
    }
}
