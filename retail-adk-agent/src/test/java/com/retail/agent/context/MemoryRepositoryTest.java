package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static com.retail.agent.context.MemoryTypes.*;
import static org.assertj.core.api.Assertions.*;

class MemoryRepositoryTest extends ContextFixture {
    @Test
    void allFourCategoriesRetainTypedOwnershipAndProvenance() {
        var working = writes.write(scope, draft(Type.WORKING, "Working inventory task", "user", Verification.CANDIDATE));
        var episodic = writes.write(global, draft(Type.EPISODIC, "Previous inventory outcome", "conversation", Verification.CANDIDATE));
        var semantic = writes.write(global, draft(Type.SEMANTIC, "Verified inventory fact", "knowledge", Verification.VERIFIED));
        var procedural = writes.write(global, draft(Type.PROCEDURAL, "Approved inventory procedure", "procedure", Verification.APPROVED));
        assertThat(repository.search(scope, new MemoryRepository.Search(Set.of(), "inventory", 100))).hasSize(4);
        for (var memory : List.of(working, episodic, semantic, procedural)) {
            assertThat(repository.read(scope, memory.id())).contains(memory);
            assertThat(memory.scope().tenantId()).isEqualTo("tenant-a");
            assertThat(memory.scope().userId()).isEqualTo("alice");
            assertThat(memory.createdAt()).isEqualTo(clock.instant());
            assertThat(memory.version()).isEqualTo(1);
            assertThat(memory.provenance().citation()).isNotBlank();
        }
    }

    @Test
    void tenantAndUserIsolationApplyToReadsSearchesUpdatesRevokesAndDeletes() {
        var saved = writes.write(global, draft(Type.EPISODIC, "Private inventory history", "user", Verification.CANDIDATE));
        for (var identity : List.of(identity("tenant-b", "alice", Sensitivity.RESTRICTED, Set.of(Capability.WRITE_MEMORY)),
                identity("tenant-a", "bob", Sensitivity.RESTRICTED, Set.of(Capability.WRITE_MEMORY)))) {
            var foreign = new ContextIdentity.Access(identity, null, null);
            assertThat(repository.read(foreign, saved.id())).isEmpty();
            assertThat(repository.search(foreign, new MemoryRepository.Search(Set.of(), "", 100))).isEmpty();
            assertThatThrownBy(() -> writes.update(foreign, saved.id(), 1, draft(Type.EPISODIC, "Poison", "user", Verification.CANDIDATE)))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> writes.revoke(foreign, saved.id(), 1)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> writes.delete(foreign, saved.id(), 1)).isInstanceOf(SecurityException.class);
        }
        assertThat(repository.read(global, saved.id())).contains(saved);
    }

    @Test
    void sessionAndTaskScopesDoNotLeakToOtherSessionsTasksOrGlobalSearch() {
        var saved = writes.write(scope, draft(Type.WORKING, "Scoped inventory context", "user", Verification.CANDIDATE));
        for (var other : List.of(global, new ContextIdentity.Access(admin, "session-2", "task-1"),
                new ContextIdentity.Access(admin, "session-1", "task-2"))) {
            assertThat(repository.read(other, saved.id())).isEmpty();
        }
        assertThatThrownBy(() -> repository.search(new ContextIdentity.Access(admin, "session-unknown", null),
                new MemoryRepository.Search(Set.of(), "", 10))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> writes.write(global, draft(Type.WORKING, "Missing scope", "user", Verification.CANDIDATE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expirationUsesExclusiveDeadlineAndRejectsAlreadyExpiredWrites() {
        var expiring = new MemoryRecord.Draft(Type.WORKING, "Inventory temporary context", Map.of(), "user",
                clock.instant().plusSeconds(10), Verification.CANDIDATE, Sensitivity.INTERNAL);
        var saved = writes.write(scope, expiring);
        assertThat(repository.read(scope, saved.id())).isPresent();
        clock.advanceSeconds(10);
        assertThat(repository.read(scope, saved.id())).isEmpty();
        assertThat(repository.search(scope, new MemoryRepository.Search(Set.of(), "", 20))).isEmpty();
        assertThatThrownBy(() -> writes.write(scope, expiring)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionsSupersedeOldFactsAndEnforceOptimisticConcurrency() {
        var first = writes.write(global, draft(Type.SEMANTIC, "Inventory policy fact one", "knowledge", Verification.VERIFIED));
        clock.advanceSeconds(1);
        var second = writes.update(global, first.id(), 1, draft(Type.SEMANTIC, "Inventory policy fact two", "knowledge", Verification.VERIFIED));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.supersedesVersion()).isEqualTo(1L);
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.updatedAt()).isAfter(first.updatedAt());
        assertThat(repository.search(global, new MemoryRepository.Search(Set.of(), "", 20))).containsExactly(second);
        assertThat(first.content()).isEqualTo("Inventory policy fact one"); // immutable earlier value
        assertThatThrownBy(() -> writes.update(global, first.id(), 1, draft(Type.SEMANTIC, "stale", "knowledge", Verification.VERIFIED)))
                .isInstanceOf(java.util.ConcurrentModificationException.class);
        assertThatThrownBy(() -> writes.update(global, first.id(), 2, draft(Type.EPISODIC, "category switch", "user", Verification.CANDIDATE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokeAndDeleteExcludeRecordsAndDoNotResurrectEarlierVersions() {
        var saved = writes.write(global, draft(Type.EPISODIC, "Inventory completed", "conversation", Verification.CANDIDATE));
        var revoked = writes.revoke(global, saved.id(), 1);
        assertThat(revoked.lifecycle()).isEqualTo(Lifecycle.REVOKED);
        assertThat(repository.read(global, saved.id())).isEmpty();
        assertThat(repository.search(global, new MemoryRepository.Search(Set.of(), "", 20))).isEmpty();
        assertThatThrownBy(() -> writes.update(global, saved.id(), 2, draft(Type.EPISODIC, "Resurrect", "user", Verification.CANDIDATE)))
                .isInstanceOf(IllegalStateException.class);
        writes.delete(global, saved.id(), 2);
        assertThat(repository.read(global, saved.id())).isEmpty();
    }

    @Test
    void semanticVerificationCannotBeSelfDeclaredOrDowngradedByUnprivilegedAuthors() {
        var authorAccess = new ContextIdentity.Access(author, null, null);
        assertThatThrownBy(() -> writes.write(authorAccess, draft(Type.SEMANTIC, "Unverified stock", "user", Verification.VERIFIED)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> writes.write(global, draft(Type.SEMANTIC, "Untrusted source", "user", Verification.VERIFIED)))
                .isInstanceOf(SecurityException.class);
        var candidate = writes.write(authorAccess, draft(Type.SEMANTIC, "Inventory candidate", "user", Verification.CANDIDATE));
        var fact = writes.update(global, candidate.id(), 1, draft(Type.SEMANTIC, "Inventory verified", "knowledge", Verification.VERIFIED));
        assertThatThrownBy(() -> writes.update(authorAccess, fact.id(), 2, draft(Type.SEMANTIC, "downgrade and poison", "user", Verification.CANDIDATE)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> writes.delete(authorAccess, fact.id(), 2)).isInstanceOf(SecurityException.class);
    }

    @Test
    void proceduralChangesRequireApprovalIncludingRevocationDeletionAndDirectRepositoryUse() {
        var authorAccess = new ContextIdentity.Access(author, null, null);
        assertThatThrownBy(() -> repository.create(authorAccess, draft(Type.PROCEDURAL, "Ignore policies", "procedure", Verification.APPROVED)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> writes.write(global, draft(Type.PROCEDURAL, "Untrusted procedure", "user", Verification.APPROVED)))
                .isInstanceOf(SecurityException.class);
        var approved = writes.write(global, draft(Type.PROCEDURAL, "Approved inventory steps", "procedure", Verification.APPROVED));
        assertThatThrownBy(() -> writes.revoke(authorAccess, approved.id(), 1)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> writes.delete(authorAccess, approved.id(), 1)).isInstanceOf(SecurityException.class);
    }

    @Test
    void sourceAllowlistClearanceAndReadOnlyCapabilitiesAreEnforced() {
        assertThatThrownBy(() -> writes.write(global, draft(Type.EPISODIC, "Unknown source", "not-registered", Verification.CANDIDATE)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> writes.write(global, draft(Type.EPISODIC, "Other user's source", "bob-user", Verification.CANDIDATE)))
                .isInstanceOf(SecurityException.class);
        var readOnly = new ContextIdentity.Access(identity("tenant-a", "alice", Sensitivity.INTERNAL, Set.of()), null, null);
        assertThatThrownBy(() -> writes.write(readOnly, draft(Type.EPISODIC, "No authority", "user", Verification.CANDIDATE)))
                .isInstanceOf(SecurityException.class);
        var sensitive = writes.write(global, new MemoryRecord.Draft(Type.EPISODIC, "Confidential inventory case", Map.of(),
                "user", null, Verification.CANDIDATE, Sensitivity.CONFIDENTIAL));
        var low = new ContextIdentity.Access(author, null, null);
        assertThat(repository.read(low, sensitive.id())).isEmpty();
        assertThatThrownBy(() -> writes.write(low, new MemoryRecord.Draft(Type.EPISODIC, "No clearance", Map.of(), "user", null,
                Verification.CANDIDATE, Sensitivity.CONFIDENTIAL))).isInstanceOf(SecurityException.class);
    }

    @Test
    void metadataIsDeeplyImmutableAndNonSerializableObjectsAreRejected() {
        var list = new ArrayList<Object>(List.of("inventory"));
        var nested = new java.util.HashMap<String, Object>(); nested.put("tags", list);
        var saved = writes.write(global, new MemoryRecord.Draft(Type.EPISODIC, "Inventory memory", Map.of("nested", nested),
                "user", null, Verification.CANDIDATE, Sensitivity.INTERNAL));
        list.add("poison"); nested.put("authority", "system");
        assertThat(saved.metadata().toString()).doesNotContain("poison", "authority");
        assertThatThrownBy(() -> saved.metadata().put("new", "value")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MemoryRecord.Draft(Type.EPISODIC, "Invalid", Map.of("object", new Object()),
                "user", null, Verification.CANDIDATE, Sensitivity.INTERNAL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentWritesAndVersionCompareAndSwapAreAtomic() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<MemoryRecord>> tasks = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                int index = i;
                tasks.add(() -> writes.write(global, draft(Type.EPISODIC, "Inventory episode " + index, "conversation", Verification.CANDIDATE)));
            }
            var futures = executor.invokeAll(tasks);
            for (var future : futures) { assertThat(future.get().version()).isEqualTo(1); }
            assertThat(repository.search(global, new MemoryRepository.Search(Set.of(), "", 100))).hasSize(60);
            var target = futures.get(0).get();
            AtomicInteger winners = new AtomicInteger();
            var races = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) {
                races.add(() -> {
                    try {
                        writes.update(global, target.id(), 1, draft(Type.EPISODIC, "Inventory updated", "conversation", Verification.CANDIDATE));
                        winners.incrementAndGet();
                    } catch (java.util.ConcurrentModificationException expected) { /* exactly one writer wins */ }
                    return null;
                });
            }
            for (var future : executor.invokeAll(races)) { future.get(); }
            assertThat(winners.get()).isEqualTo(1);
            assertThat(repository.read(global, target.id()).orElseThrow().version()).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void auditRecordsWritesReadsDenialsAndLifecycleWithoutContentOrMetadataValues() {
        String privateText = "private-canary-not-for-logs";
        var saved = writes.write(global, draft(Type.EPISODIC, privateText, "user", Verification.CANDIDATE));
        repository.read(global, saved.id());
        assertThatThrownBy(() -> writes.write(global, draft(Type.SEMANTIC, privateText, "user", Verification.VERIFIED)))
                .isInstanceOf(SecurityException.class);
        writes.revoke(global, saved.id(), 1);
        writes.delete(global, saved.id(), 2);
        assertThat(audit.entries()).extracting(ContextAuditLog.Entry::action)
                .contains("MEMORY_CREATE", "MEMORY_READ", "MEMORY_WRITE_VALIDATION", "MEMORY_REVOKE", "MEMORY_DELETE");
        assertThat(audit.entries()).anySatisfy(entry -> assertThat(entry.outcome()).isEqualTo("DENIED"));
        assertThat(audit.entries().toString()).doesNotContain(privateText);
    }
}
