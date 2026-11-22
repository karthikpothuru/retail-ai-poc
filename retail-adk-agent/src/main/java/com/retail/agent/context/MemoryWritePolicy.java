package com.retail.agent.context;

import java.time.Clock;
import static com.retail.agent.context.MemoryTypes.*;

/** Rechecked at both service and storage boundaries so direct repository use cannot escalate trust. */
public final class MemoryWritePolicy {
    private final SourceRegistry sources;
    private final Clock clock;
    public MemoryWritePolicy(SourceRegistry sources, Clock clock) {
        this.sources = java.util.Objects.requireNonNull(sources);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public SourceRegistry.Source validate(ContextIdentity.Access access, MemoryRecord.Draft draft) {
        authorizeWrite(access);
        var source = sources.resolve(access, draft.sourceId());
        if (draft.sensitivity().ordinal() > access.identity().clearance().ordinal()) {
            throw new SecurityException("Insufficient sensitivity clearance");
        }
        if (draft.expiresAt() != null && !draft.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("New memory must not already be expired");
        }
        switch (draft.type()) {
            case WORKING -> {
                if (access.sessionId() == null && access.taskId() == null) {
                    throw new IllegalArgumentException("Working memory requires a session or task scope");
                }
                candidate(draft);
                userEvidence(source);
            }
            case EPISODIC -> { candidate(draft); userEvidence(source); }
            case SEMANTIC -> {
                if (draft.verification() == Verification.APPROVED) {
                    throw new SecurityException("Semantic facts require verification, not procedure approval");
                }
                if (draft.verification() == Verification.VERIFIED) {
                    if (!access.identity().capabilities().contains(Capability.VERIFY_SEMANTIC)
                            || (source.origin() != Origin.KNOWLEDGE && source.origin() != Origin.TOOL_RESULT)) {
                        throw new SecurityException("Semantic verification is not authorized");
                    }
                } else {
                    if (source.origin() != Origin.KNOWLEDGE) { userEvidence(source); }
                }
            }
            case PROCEDURAL -> {
                if (!access.identity().capabilities().contains(Capability.APPROVE_PROCEDURE)
                        || draft.verification() != Verification.APPROVED || source.origin() != Origin.PROCEDURE) {
                    throw new SecurityException("Approved procedural writes require procedure authority and provenance");
                }
            }
        }
        return source;
    }

    static void authorizeWrite(ContextIdentity.Access access) {
        access.authorize();
        if (!access.identity().capabilities().contains(Capability.WRITE_MEMORY)) {
            throw new SecurityException("Memory writes are not authorized");
        }
    }

    private static void candidate(MemoryRecord.Draft draft) {
        if (draft.verification() != Verification.CANDIDATE) {
            throw new SecurityException("Interaction memory cannot declare authoritative facts or instructions");
        }
    }

    private static void userEvidence(SourceRegistry.Source source) {
        if (source.origin() != Origin.USER_INPUT && source.origin() != Origin.CONVERSATION
                && source.origin() != Origin.TOOL_RESULT && source.origin() != Origin.RETRIEVED_EVIDENCE) {
            throw new SecurityException("Source origin is invalid for interaction/candidate memory");
        }
    }
}
