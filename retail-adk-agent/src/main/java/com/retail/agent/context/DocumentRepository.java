package com.retail.agent.context;

import java.time.Instant;
import java.util.*;
import static com.retail.agent.context.EnterpriseDocument.*;

/** Trusted host storage boundary, separate from MemoryRepository. Never expose raw repository calls as tools. */
public interface DocumentRepository {
    Version activate(ContextIdentity.Access access,Draft draft,Extracted extracted,List<Indexed> chunks,Instant now);
    List<Evidence> search(ContextIdentity.Access access,EmbeddingProvider.Vector query,int limit,double threshold,Instant now,UUID retrievalOperation);
    default List<Evidence> search(ContextIdentity.Access access, EmbeddingProvider.Vector query, int limit,
                                  double threshold, Instant now, UUID retrievalOperation, Set<UUID> documentIds) {
        return search(access, query, limit, threshold, now, retrievalOperation).stream()
                .filter(evidence -> documentIds.contains(evidence.citation().documentId())).toList();
    }
    boolean current(ContextIdentity.Access access,Evidence evidence,Instant now);
    Head head(ContextIdentity.Access access,UUID id);
    Head lifecycle(ContextIdentity.Access access,UUID id,long version,Status status);
    Head acl(ContextIdentity.Access access,UUID id,long expectedAcl,Set<String> readers);
    void retention(ContextIdentity.Access access,UUID id,long version,boolean hold,Instant retainUntil);
    int cleanup(ContextIdentity.Access access,UUID id,long version,Instant now);
}
