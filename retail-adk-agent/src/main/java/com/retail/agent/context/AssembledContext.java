package com.retail.agent.context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.retail.agent.context.MemoryTypes.*;

public record AssembledContext(List<Block> trustedInstructions, List<Block> referenceData, Manifest manifest) {
    public enum Kind { SYSTEM_INSTRUCTION, POLICY, USER_GOAL, CONVERSATION, RETRIEVED_EVIDENCE, TOOL_RESULT,
        WORKING_MEMORY, EPISODIC_MEMORY, SEMANTIC_FACT, APPROVED_PROCEDURE }
    public enum Authority { TRUSTED_INSTRUCTION, UNTRUSTED_REFERENCE_DATA }

    public record Block(String id, Kind kind, Authority authority, String content, SourceRegistry.Source source,
                        Verification verification, Sensitivity sensitivity, Map<String, Object> metadata,
                        UUID memoryId, long memoryVersion) {
        public Block {
            metadata = StructuredValues.metadata(metadata);
        }
        Map<String, Object> payload() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", id); fields.put("kind", kind.name()); fields.put("authority", authority.name());
            fields.put("content", content); fields.put("provenance", source.asMap());
            fields.put("verification", verification.name()); fields.put("sensitivity", sensitivity.name());
            fields.put("metadata", metadata);
            if (memoryId != null) { fields.put("memoryId", memoryId.toString()); fields.put("memoryVersion", memoryVersion); }
            return Map.copyOf(fields);
        }
    }

    public record Selection(String blockId, UUID memoryId, long memoryVersion, String sourceId,
                            Verification verification, Sensitivity sensitivity, int estimatedTokens) {}
    public record Omission(String blockId, String reason) {}
    public record Manifest(UUID assemblyId, Instant assembledAt, String estimatorId, int tokenBudget,
                           int usedTokens, List<Selection> selections, List<Omission> omissions) {
        public Manifest { selections = List.copyOf(selections); omissions = List.copyOf(omissions); }
    }

    public AssembledContext {
        trustedInstructions = List.copyOf(trustedInstructions);
        referenceData = List.copyOf(referenceData);
    }

    /** Data is JSON-escaped and never concatenated into a system instruction string. */
    public String render() { return render(trustedInstructions, referenceData); }
    static String render(List<Block> trusted, List<Block> data) {
        return StructuredValues.json(Map.of("trustedInstructions", trusted.stream().map(Block::payload).toList(),
                "referenceData", data.stream().map(Block::payload).toList()));
    }
}
