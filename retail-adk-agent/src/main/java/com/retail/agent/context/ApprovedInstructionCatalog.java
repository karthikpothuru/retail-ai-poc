package com.retail.agent.context;

import java.util.Map;
import static com.retail.agent.context.MemoryTypes.*;

/** Host-managed immutable instruction bodies. Requests select IDs, never supply trusted text. */
public final class ApprovedInstructionCatalog {
    public record Instruction(String id, String content, String sourceId, Origin kind, Sensitivity sensitivity) {
        public Instruction {
            ContextIdentity.identifier(id); ContextIdentity.identifier(sourceId); StructuredValues.content(content);
            if (kind != Origin.SYSTEM && kind != Origin.POLICY) { throw new IllegalArgumentException("Invalid instruction kind"); }
            java.util.Objects.requireNonNull(sensitivity);
        }
    }
    private final Map<String, Instruction> entries;
    private final SourceRegistry sources;
    public ApprovedInstructionCatalog(Map<String, Instruction> entries, SourceRegistry sources) {
        this.entries = Map.copyOf(entries);
        this.sources = java.util.Objects.requireNonNull(sources);
        this.entries.forEach((id, entry) -> {
            if (!id.equals(entry.id())) { throw new IllegalArgumentException("Instruction ID mismatch"); }
        });
    }

    Instruction resolve(ContextIdentity.Access access, String id, Origin kind) {
        var entry = entries.get(id);
        if (entry == null || entry.kind() != kind || entry.sensitivity().ordinal() > access.identity().clearance().ordinal()
                || sources.resolve(access, entry.sourceId()).origin() != kind) {
            throw new SecurityException("Instruction or policy is not approved for this scope");
        }
        return entry;
    }
}
