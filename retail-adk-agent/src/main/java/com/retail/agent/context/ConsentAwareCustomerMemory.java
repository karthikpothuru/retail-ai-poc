package com.retail.agent.context;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.retail.agent.context.DemoCustomerContextClient.*;
import static com.retail.agent.context.MemoryTypes.*;

/** Host-only Phase 8.4A capability. Public RAG agents and MCP tools do not receive this capability. */
public final class ConsentAwareCustomerMemory {
    static final String TENANT = "demo-customer-personalization";
    private static final String SOURCE = "demo-working-preference";
    private static final Duration RETENTION = Duration.ofMinutes(30);

    public enum Status { READY, NO_SESSION, CONSENT_DISABLED, CONTEXT_CHANGED, UNAVAILABLE }

    public enum DetailLevel {
        CONCISE("Preferred detail level: concise"),
        DETAILED("Preferred detail level: detailed");

        private final String statement;

        DetailLevel(String statement) {
            this.statement = statement;
        }
    }

    public record Result(Status status, List<GovernedMemoryService.View> memory) {
        public Result {
            memory = List.copyOf(memory);
        }
    }

    private final Function<SessionCookie, Optional<Snapshot>> context;
    private final TrustedCallerBoundary.Deployment deployment;
    private final GovernedMemoryService governed;
    private final Clock clock;

    public ConsentAwareCustomerMemory(DemoCustomerContextClient customer, MemoryRepository repository,
                                      GovernanceAudit audit, Clock clock) {
        this(customer::resolve, repository, audit, clock);
    }

    ConsentAwareCustomerMemory(Function<SessionCookie, Optional<Snapshot>> context, MemoryRepository repository,
                               GovernanceAudit audit, Clock clock) {
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
        deployment = TrustedCallerBoundary.bootstrap(clock);
        governed = new GovernedMemoryService(deployment.boundary(), repository, sources(), audit);
    }

    public static MemoryWritePolicy writePolicy(Clock clock) {
        return new MemoryWritePolicy(sources(), clock);
    }

    private static SourceRegistry sources() {
        return new SourceRegistry(Map.of(SOURCE, new SourceRegistry.Source(SOURCE, TENANT, null,
                Origin.USER_INPUT, "Explicit synthetic preference selected by the local host")));
    }

    public Result read(SessionCookie cookie) {
        return scopedRead(cookie).result();
    }

    record Personalization(Snapshot binding, java.util.UUID memoryId, long version, DetailLevel level) {}
    private record ScopedResult(Result result, Snapshot binding) {}

    Optional<Personalization> personalization(SessionCookie cookie) {
        var read = scopedRead(cookie);
        if (read.result().status() != Status.READY || read.result().memory().isEmpty()) {
            return Optional.empty();
        }
        var view = read.result().memory().get(0);
        var level = DetailLevel.CONCISE.statement.equals(view.content()) ? DetailLevel.CONCISE : DetailLevel.DETAILED;
        return Optional.of(new Personalization(read.binding(), view.id(), view.version(), level));
    }

    boolean stillCurrent(SessionCookie cookie, Personalization expected) {
        return personalization(cookie).filter(expected::equals).isPresent();
    }

    private ScopedResult scopedRead(SessionCookie cookie) {
        return withinConsent(cookie, TrustedCallerBoundary.Operation.READ, caller -> governed.searchExactScope(caller,
                        new MemoryRepository.Search(Set.of(Type.WORKING), "", 20)).stream()
                .filter(view -> !view.redacted() && view.signals().isEmpty())
                .filter(view -> isApprovedPreference(view.content()))
                .toList());
    }

    public Result remember(SessionCookie cookie, DetailLevel level) {
        Objects.requireNonNull(level);
        return withinConsent(cookie, TrustedCallerBoundary.Operation.CREATE, caller -> {
            var preference = new MemoryRecord.Draft(Type.WORKING, level.statement, Map.of(), SOURCE,
                    clock.instant().plus(RETENTION), Verification.CANDIDATE, Sensitivity.CONFIDENTIAL);
            return List.of(governed.create(caller, preference));
        }).result();
    }

    private ScopedResult withinConsent(SessionCookie cookie, TrustedCallerBoundary.Operation operation,
                                Function<TrustedCallerBoundary.Caller, List<GovernedMemoryService.View>> action) {
        if (cookie == null) {
            return empty(Status.NO_SESSION);
        }
        try {
            var before = context.apply(cookie);
            if (before.isEmpty()) {
                return empty(Status.NO_SESSION);
            }
            var binding = before.orElseThrow();
            if (!binding.personalizationConsent()) {
                return empty(Status.CONSENT_DISABLED);
            }

            var access = access(binding, operation == TrustedCallerBoundary.Operation.CREATE);
            var grants = operation == TrustedCallerBoundary.Operation.CREATE
                    ? Set.of(operation, TrustedCallerBoundary.Operation.REVOKE) : Set.of(operation);
            var credential = deployment.provisioner().issue(access.identity(), grants,
                    Sensitivity.CONFIDENTIAL, clock.instant().plusSeconds(60));
            try {
                var caller = governed.authenticate(credential.token(), access.sessionId(), null);
                var result = action.apply(caller);
                // The next request must also resolve consent again. Never cache a successful resolution.
                try {
                    if (before.equals(context.apply(cookie))) {
                        return new ScopedResult(new Result(Status.READY, result), binding);
                    }
                } catch (RuntimeException unavailable) {
                    discardWrite(operation, caller, result);
                    return empty(Status.UNAVAILABLE);
                }
                discardWrite(operation, caller, result);
                return empty(Status.CONTEXT_CHANGED);
            } finally {
                deployment.provisioner().revoke(credential.authenticationId());
            }
        } catch (RuntimeException failed) {
            return empty(Status.UNAVAILABLE);
        }
    }

    private void discardWrite(TrustedCallerBoundary.Operation operation, TrustedCallerBoundary.Caller caller,
                              List<GovernedMemoryService.View> result) {
        if (operation == TrustedCallerBoundary.Operation.CREATE) {
            var created = result.get(0);
            try {
                governed.revoke(caller, created.id(), created.version());
            } catch (RuntimeException cleanupUnavailable) {
                // Governance audits failed cleanup. A changed binding fences the old namespace, and expiry still applies.
            }
        }
    }

    static ContextIdentity.Access access(Snapshot snapshot, boolean writable) {
        String session = "selection-" + snapshot.selectionId() + "-" + snapshot.consentEpoch();
        var identity = new ContextIdentity(TENANT, "customer-" + snapshot.customerId(), Set.of(session), Set.of(),
                writable ? Set.of(Capability.WRITE_MEMORY) : Set.of(), Sensitivity.CONFIDENTIAL);
        return new ContextIdentity.Access(identity, session, null);
    }

    private static boolean isApprovedPreference(String value) {
        return DetailLevel.CONCISE.statement.equals(value) || DetailLevel.DETAILED.statement.equals(value);
    }

    private static ScopedResult empty(Status status) {
        return new ScopedResult(new Result(status, List.of()), null);
    }
}
