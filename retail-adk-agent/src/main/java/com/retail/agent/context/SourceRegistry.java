package com.retail.agent.context;

import java.util.Map;
import java.util.Objects;
import static com.retail.agent.context.MemoryTypes.*;

/** Server configuration: reference IDs resolve to canonical provenance, never arbitrary external URLs. */
public final class SourceRegistry {
    public record Source(String id, String tenantId, String ownerUserId, Origin origin, String citation) {
        public Source {
            ContextIdentity.identifier(id); ContextIdentity.identifier(tenantId);
            if (ownerUserId != null) { ContextIdentity.identifier(ownerUserId); }
            Objects.requireNonNull(origin);
            StructuredValues.content(citation);
        }
        Map<String, Object> asMap() { return Map.of("sourceId", id, "origin", origin.name(), "citation", citation); }
    }

    private final Map<String, Source> sources;
    public SourceRegistry(Map<String, Source> sources) {
        this.sources = Map.copyOf(sources);
        this.sources.forEach((id, source) -> {
            if (!id.equals(source.id())) { throw new IllegalArgumentException("Source ID mismatch"); }
        });
    }

    public Source resolve(ContextIdentity.Access access, String id) {
        access.authorize();
        Source source = sources.get(id);
        if (source == null || !source.tenantId().equals(access.identity().tenantId())
                || (source.ownerUserId() != null && !source.ownerUserId().equals(access.identity().userId()))) {
            throw new SecurityException("Source reference is not authorized or registered");
        }
        return source;
    }
}
