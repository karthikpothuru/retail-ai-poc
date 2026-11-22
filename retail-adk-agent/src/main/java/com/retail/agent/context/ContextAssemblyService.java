package com.retail.agent.context;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import static com.retail.agent.context.AssembledContext.*;
import static com.retail.agent.context.MemoryTypes.*;

/** Pure deterministic selection/serialization over authorized data; no model calls or automatic memory writes. */
public final class ContextAssemblyService {
    public record Data(String id, String content, String sourceId, Sensitivity sensitivity) {
        public Data {
            ContextIdentity.identifier(id); StructuredValues.content(content); ContextIdentity.identifier(sourceId);
            java.util.Objects.requireNonNull(sensitivity);
        }
    }
    public record Request(Data userGoal, List<Data> conversation, List<Data> retrievedEvidence, List<Data> toolResults,
                          List<String> instructionIds, List<String> policyIds, int tokenBudget, int maximumMemories) {
        public Request {
            java.util.Objects.requireNonNull(userGoal);
            conversation = bounded(conversation); retrievedEvidence = bounded(retrievedEvidence); toolResults = bounded(toolResults);
            instructionIds = bounded(instructionIds); policyIds = bounded(policyIds);
            if (tokenBudget < 1 || tokenBudget > 1_000_000 || maximumMemories < 0 || maximumMemories > 100) {
                throw new IllegalArgumentException("Invalid context budget");
            }
        }
        private static <T> List<T> bounded(List<T> items) {
            if (items.size() > 64) { throw new IllegalArgumentException("Too many context inputs"); }
            return List.copyOf(items);
        }
    }
    public record TokenEstimator(String id, ToIntFunction<String> counter) {
        public TokenEstimator { ContextIdentity.identifier(id); java.util.Objects.requireNonNull(counter); }
        public int estimate(String text) {
            int count = counter.applyAsInt(text);
            if (count < 0) { throw new IllegalArgumentException("Estimator returned a negative count"); }
            return count;
        }
        public static TokenEstimator utf8Bytes() {
            return new TokenEstimator("utf8-byte-estimate", text -> text.getBytes(StandardCharsets.UTF_8).length);
        }
    }
    public static final class BudgetExceededException extends IllegalArgumentException {
        public BudgetExceededException() { super("Mandatory approved instructions, policies and user goal exceed context budget"); }
    }
    private record Candidate(Block block, int priority, int displayOrder, Instant updatedAt) {}
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private final MemoryRepository repository;
    private final SourceRegistry sources;
    private final ApprovedInstructionCatalog catalog;
    private final ContextAuditLog audit;
    private final Clock clock;
    private final TokenEstimator estimator;

    public ContextAssemblyService(MemoryRepository repository, SourceRegistry sources, ApprovedInstructionCatalog catalog,
                                  ContextAuditLog audit, Clock clock, TokenEstimator estimator) {
        this.repository = java.util.Objects.requireNonNull(repository); this.sources = java.util.Objects.requireNonNull(sources);
        this.catalog = java.util.Objects.requireNonNull(catalog); this.audit = java.util.Objects.requireNonNull(audit);
        this.clock = java.util.Objects.requireNonNull(clock); this.estimator = java.util.Objects.requireNonNull(estimator);
    }

    public AssembledContext assemble(ContextIdentity.Access access, Request request) {
        UUID assemblyId = UUID.randomUUID();
        try { return assembleAuthorized(access, request, assemblyId); }
        catch (RuntimeException failure) {
            audit.record(access, "CONTEXT_ASSEMBLY", failure instanceof SecurityException ? "DENIED" : "REJECTED", null, 0, assemblyId);
            throw failure;
        }
    }

    private AssembledContext assembleAuthorized(ContextIdentity.Access access, Request request, UUID assemblyId) {
        access.authorize();
        List<Block> trusted = new ArrayList<>();
        instructions(access, request.instructionIds(), Origin.SYSTEM, trusted);
        instructions(access, request.policyIds(), Origin.POLICY, trusted);
        Block goal = data(access, request.userGoal(), Kind.USER_GOAL, Set.of(Origin.USER_INPUT));
        List<Block> data = new ArrayList<>(List.of(goal));
        if (cost(trusted, data) > request.tokenBudget()) { throw new BudgetExceededException(); }
        Set<String> goalWords = words(goal.content());
        List<Candidate> candidates = new ArrayList<>();
        List<Omission> omissions = new ArrayList<>();
        addData(access, request.conversation(), Kind.CONVERSATION, Set.of(Origin.CONVERSATION, Origin.USER_INPUT), 0, 20, goalWords, candidates);
        addData(access, request.retrievedEvidence(), Kind.RETRIEVED_EVIDENCE, Set.of(Origin.RETRIEVED_EVIDENCE), 100, 40, goalWords, candidates);
        addData(access, request.toolResults(), Kind.TOOL_RESULT, Set.of(Origin.TOOL_RESULT), 200, 60, goalWords, candidates);
        var memories = repository.search(access, new MemoryRepository.Search(Set.of(), "", 1000));
        List<Candidate> memoryCandidates = new ArrayList<>();
        for (MemoryRecord memory : memories) {
            if (!memory.scope().permits(access) || memory.sensitivity().ordinal() > access.identity().clearance().ordinal()) {
                throw new SecurityException("Repository returned a memory outside the authorized scope");
            }
            String id = "memory:" + memory.id();
            var current = repository.read(access, memory.id());
            if (current.isEmpty() || current.get().version() != memory.version() || !memory.activeAt(clock.instant())) {
                omissions.add(new Omission(id, "STALE_BEFORE_ASSEMBLY"));
                continue;
            }
            if ((memory.type() == Type.SEMANTIC && memory.verification() != Verification.VERIFIED)
                    || (memory.type() == Type.PROCEDURAL && memory.verification() != Verification.APPROVED)) {
                omissions.add(new Omission(id, "UNVERIFIED_OR_UNAPPROVED"));
                continue;
            }
            int overlap = overlap(goalWords, words(memory.content() + " " + StructuredValues.json(memory.metadata())));
            boolean taskWorking = memory.type() == Type.WORKING && (memory.scope().taskId() != null || memory.scope().sessionId() != null);
            if (overlap == 0 && !taskWorking) {
                omissions.add(new Omission(id, "NOT_RELEVANT"));
                continue;
            }
            // Re-resolve canonical provenance at use time; source allowlist removal invalidates old authority.
            SourceRegistry.Source source = sources.resolve(access, memory.provenance().id());
            if (!source.equals(memory.provenance())) { throw new SecurityException("Memory provenance no longer matches registry"); }
            if ((memory.type() == Type.SEMANTIC && source.origin() != Origin.KNOWLEDGE && source.origin() != Origin.TOOL_RESULT)
                    || (memory.type() == Type.PROCEDURAL && source.origin() != Origin.PROCEDURE)) {
                throw new SecurityException("Authoritative memory has invalid source provenance");
            }
            Kind kind = switch (memory.type()) {
                case WORKING -> Kind.WORKING_MEMORY;
                case EPISODIC -> Kind.EPISODIC_MEMORY;
                case SEMANTIC -> Kind.SEMANTIC_FACT;
                case PROCEDURAL -> Kind.APPROVED_PROCEDURE;
            };
            int authorityWeight = memory.type() == Type.SEMANTIC ? 70 : memory.type() == Type.PROCEDURAL ? 50 : 30;
            int specificity = memory.scope().taskId() != null ? 20 : memory.scope().sessionId() != null ? 10 : 0;
            var block = new Block(id, kind, Authority.UNTRUSTED_REFERENCE_DATA, memory.content(), source,
                    memory.verification(), memory.sensitivity(), memory.metadata(), memory.id(), memory.version());
            memoryCandidates.add(new Candidate(block, overlap * 10 + authorityWeight + specificity, 0, memory.updatedAt()));
        }
        var ranking = Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparing(Candidate::updatedAt, Comparator.reverseOrder()).thenComparing(candidate -> candidate.block().id());
        memoryCandidates.sort(ranking);
        for (int index = 0; index < memoryCandidates.size(); index++) {
            var candidate = memoryCandidates.get(index);
            candidates.add(new Candidate(candidate.block(), candidate.priority(), 1000 + index, candidate.updatedAt()));
        }

        // Deduplicate reference data only; required policies and goal cannot be removed by copied evidence.
        Map<String, Candidate> unique = new HashMap<>();
        for (Candidate candidate : candidates) {
            String key = normalized(candidate.block().content());
            Candidate prior = unique.get(key);
            if (prior == null) { unique.put(key, candidate); }
            else {
                int trustDifference = trust(candidate.block().verification()) - trust(prior.block().verification());
                boolean preferNew = trustDifference > 0 || (trustDifference == 0 && ranking.compare(candidate, prior) < 0);
                omissions.add(new Omission(preferNew ? prior.block().id() : candidate.block().id(), "DUPLICATE_CONTENT"));
                if (preferNew) { unique.put(key, candidate); }
            }
        }
        List<Candidate> selected = new ArrayList<>();
        int memoryCount = 0;
        for (Candidate candidate : unique.values().stream().sorted(ranking).toList()) {
            boolean memory = candidate.block().memoryId() != null;
            if (memory && memoryCount >= request.maximumMemories()) {
                omissions.add(new Omission(candidate.block().id(), "MEMORY_LIMIT")); continue;
            }
            var tentative = new ArrayList<>(selected);
            tentative.add(candidate);
            var renderedData = ordered(goal, tentative);
            if (cost(trusted, renderedData) > request.tokenBudget()) {
                omissions.add(new Omission(candidate.block().id(), "TOKEN_BUDGET")); continue;
            }
            selected.add(candidate);
            if (memory) { memoryCount++; }
        }
        data = ordered(goal, selected);
        int used = cost(trusted, data);
        List<Selection> selections = java.util.stream.Stream.concat(trusted.stream(), data.stream())
                .map(block -> new Selection(block.id(), block.memoryId(), block.memoryVersion(), block.source().id(),
                        block.verification(), block.sensitivity(), estimator.estimate(StructuredValues.json(block.payload())))).toList();
        omissions.sort(Comparator.comparing(Omission::blockId).thenComparing(Omission::reason));
        var manifest = new Manifest(assemblyId, clock.instant(), estimator.id(), request.tokenBudget(), used, selections, omissions);
        audit.record(access, "CONTEXT_ASSEMBLY", "SUCCESS", null, selections.size(), assemblyId);
        return new AssembledContext(trusted, data, manifest);
    }

    private void instructions(ContextIdentity.Access access, List<String> ids, Origin origin, List<Block> target) {
        for (String id : ids.stream().distinct().sorted().toList()) {
            var entry = catalog.resolve(access, id, origin);
            target.add(new Block("instruction:" + id, origin == Origin.SYSTEM ? Kind.SYSTEM_INSTRUCTION : Kind.POLICY,
                    Authority.TRUSTED_INSTRUCTION, entry.content(), sources.resolve(access, entry.sourceId()),
                    Verification.APPROVED, entry.sensitivity(), Map.of(), null, 0));
        }
    }

    private Block data(ContextIdentity.Access access, Data input, Kind kind, Set<Origin> allowed) {
        var source = sources.resolve(access, input.sourceId());
        if (!allowed.contains(source.origin()) || input.sensitivity().ordinal() > access.identity().clearance().ordinal()) {
            throw new SecurityException("Context input source or sensitivity is not authorized");
        }
        return new Block(kind.name() + ":" + input.id(), kind, Authority.UNTRUSTED_REFERENCE_DATA,
                input.content(), source, Verification.CANDIDATE, input.sensitivity(), Map.of(), null, 0);
    }

    private void addData(ContextIdentity.Access access, List<Data> inputs, Kind kind, Set<Origin> origins,
                         int offset, int priority, Set<String> goal, List<Candidate> target) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            var block = data(access, inputs.get(i), kind, origins);
            if (!ids.add(block.id())) { throw new IllegalArgumentException("Duplicate context input ID"); }
            target.add(new Candidate(block, priority + 10 * overlap(goal, words(block.content())), offset + i, Instant.EPOCH));
        }
    }

    private static List<Block> ordered(Block goal, List<Candidate> selected) {
        List<Block> result = new ArrayList<>(List.of(goal));
        selected.stream().sorted(Comparator.comparingInt(Candidate::displayOrder)).forEach(candidate -> result.add(candidate.block()));
        return result;
    }
    private int cost(List<Block> trusted, List<Block> data) { return estimator.estimate(AssembledContext.render(trusted, data)); }
    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        WORD.matcher(text.toLowerCase(Locale.ROOT)).results().forEach(match -> words.add(match.group()));
        return words;
    }
    private static String normalized(String text) {
        // Preserve signs, decimals and punctuation: "variance -10" is not a duplicate of "variance 10".
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    private static int overlap(Set<String> first, Set<String> second) { return (int) first.stream().filter(second::contains).count(); }
    private static int trust(Verification verification) {
        return switch (verification) { case CANDIDATE -> 0; case APPROVED -> 1; case VERIFIED -> 2; };
    }
}
