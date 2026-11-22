package com.retail.agent.context;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.retail.agent.context.EnterpriseDocument.*;
import static com.retail.agent.context.TrustedCallerBoundary.Operation.*;

/** Trusted local ingestion and retrieval boundary. Prompts, files and ACL metadata never establish identity. */
public final class GovernedDocumentService {
    public record Query(String text,int limit,double minimumScore,int evidenceBudget){public Query{StructuredValues.content(text);if(limit<1||limit>50||!Double.isFinite(minimumScore)||minimumScore< -1||minimumScore>1||evidenceBudget<1||evidenceBudget>100000)throw new IllegalArgumentException("Invalid document retrieval bounds");}}
    public record Decision(String citation,String reason){}
    public record Retrieval(UUID operation,List<Evidence> evidence,List<Decision> decisions){public Retrieval{evidence=List.copyOf(evidence);decisions=List.copyOf(decisions);}}
    private final TrustedCallerBoundary boundary;private final DocumentRepository repository;private final SourceRegistry sources;
    private final DocumentExtractor extractor;private final DocumentChunker chunker;private final EmbeddingProvider provider;private final GovernanceAudit audit;private final Clock clock;
    private final ContextAuditLog localAudit;
    public GovernedDocumentService(TrustedCallerBoundary boundary,DocumentRepository repository,SourceRegistry sources,DocumentExtractor extractor,DocumentChunker chunker,EmbeddingProvider provider,GovernanceAudit audit,Clock clock){this.boundary=Objects.requireNonNull(boundary);this.repository=Objects.requireNonNull(repository);this.sources=Objects.requireNonNull(sources);this.extractor=Objects.requireNonNull(extractor);this.chunker=Objects.requireNonNull(chunker);this.provider=Objects.requireNonNull(provider);this.audit=Objects.requireNonNull(audit);this.clock=Objects.requireNonNull(clock);this.localAudit=new ContextAuditLog(clock);}
    List<ContextAuditLog.Entry> auditEntries(){return localAudit.entries();}
    private ContextIdentity.Access access(TrustedCallerBoundary.Caller caller,TrustedCallerBoundary.Operation operation){var a=boundary.require(caller,operation);var i=a.identity();return new ContextIdentity.Access(new ContextIdentity(i.tenantId(),i.userId(),i.sessions(),i.tasks(),i.capabilities(),caller.disclosure()),a.sessionId(),a.taskId());}
    private <T>T run(TrustedCallerBoundary.Caller caller,TrustedCallerBoundary.Operation op,Supplier<T> work){try{var a=access(caller,op);T result=work.get();localAudit.record(a,op.name(),"SUCCESS",null,0);return result;}catch(RuntimeException failure){boolean known=boundary.authentic(caller);audit.independent(GovernanceAudit.event(caller,known,op.name(),failure instanceof SecurityException?"DENIED":"ROLLED_BACK",failure instanceof SecurityException?"DOCUMENT_POLICY":"DOCUMENT_OPERATION_FAILED",null,null),known?caller.access():null);throw failure;}}
    public Head ingest(TrustedCallerBoundary.Caller caller,Draft draft,Path relative){return run(caller,DOCUMENT_INGEST,()->{
        var a=access(caller,DOCUMENT_INGEST);source(a,draft.sourceId());if(draft.classification().ordinal()>a.identity().clearance().ordinal()||draft.expiresAt()!=null&&!draft.expiresAt().isAfter(clock.instant())||draft.sourceModifiedAt().isAfter(clock.instant().plusSeconds(300)))throw new SecurityException("Document classification/timestamp policy");
        if(draft.expectedVersion()>0){var old=repository.head(a,draft.id());if(draft.classification().ordinal()<old.classification().ordinal())throw new SecurityException("Document declassification requires external review");}
        var extracted=extractor.extract(relative,draft.mime());var chunks=chunker.chunks(a.identity().tenantId(),draft.id(),draft.expectedVersion()+1,extracted);
        var indexed=chunks.stream().map(c->new Indexed(c,check(provider.embedDocument(c.text())),1)).toList();
        return repository.activate(access(caller,DOCUMENT_INGEST),draft,extracted,indexed,clock.instant()).head();
    });}
    public Retrieval retrieve(TrustedCallerBoundary.Caller caller,Query query){return retrieve(caller,query,null);}
    Retrieval retrieve(TrustedCallerBoundary.Caller caller,Query query,Set<UUID> documentIds){return run(caller,DOCUMENT_READ,()->{
        var a=access(caller,DOCUMENT_READ);UUID operation=UUID.randomUUID();var vector=check(provider.embedQuery(query.text()));
        int limit=Math.min(100,query.limit()*4);
        var authorized=access(caller,DOCUMENT_READ);
        var now=clock.instant();
        var candidates=documentIds==null?repository.search(authorized,vector,limit,query.minimumScore(),now,operation)
                :repository.search(authorized,vector,limit,query.minimumScore(),now,operation,Set.copyOf(documentIds));
        var selected=new ArrayList<Evidence>();var decisions=new ArrayList<Decision>();var hashes=new HashSet<String>();int used=0;
        for(var evidence:candidates){if(documentIds!=null&&!documentIds.contains(evidence.citation().documentId()))throw new SecurityException("Document outside requested evidence set");source(a,evidence.citation().sourceId());String reason=null;String hash=hash(evidence.text().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "));
            boolean overlap=selected.stream().anyMatch(old->old.citation().documentId().equals(evidence.citation().documentId())&&old.citation().location().equals(evidence.citation().location())&&old.citation().start()<evidence.citation().end()&&evidence.citation().start()<old.citation().end());
            int cost=render(evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(!repository.current(access(caller,DOCUMENT_READ),evidence,clock.instant()))continue;
            if(hashes.contains(hash)||overlap)reason="DUPLICATE_OR_OVERLAP";else if(selected.size()>=query.limit())reason="DOCUMENT_LIMIT";else if(cost+used>query.evidenceBudget())reason="DOCUMENT_EVIDENCE_BUDGET";
            if(reason==null){selected.add(evidence);hashes.add(hash);used+=cost;decisions.add(new Decision(evidence.citation().marker(),"SELECTED"));}else decisions.add(new Decision(evidence.citation().marker(),reason));
        }localAudit.record(a,"DOCUMENT_RETRIEVAL","SUCCESS",null,selected.size(),operation);return new Retrieval(operation,selected,decisions);
    });}
    public void revalidate(TrustedCallerBoundary.Caller caller,List<Evidence> evidence){run(caller,DOCUMENT_READ,()->{var a=access(caller,DOCUMENT_READ);for(var e:evidence){source(a,e.citation().sourceId());if(!repository.current(a,e,clock.instant()))throw new SecurityException("Document evidence revoked, changed or inaccessible");}return null;});}
    public Head revoke(TrustedCallerBoundary.Caller c,UUID id,long version){return run(c,DOCUMENT_MANAGE,()->repository.lifecycle(access(c,DOCUMENT_MANAGE),id,version,Status.REVOKED));}
    public Head delete(TrustedCallerBoundary.Caller c,UUID id,long version){return run(c,DOCUMENT_MANAGE,()->repository.lifecycle(access(c,DOCUMENT_MANAGE),id,version,Status.DELETED));}
    public Head acl(TrustedCallerBoundary.Caller c,UUID id,long expected,Set<String> readers){var copy=Set.copyOf(readers);if(copy.size()>100)throw new IllegalArgumentException("ACL too large");copy.forEach(ContextIdentity::identifier);return run(c,DOCUMENT_MANAGE,()->repository.acl(access(c,DOCUMENT_MANAGE),id,expected,copy));}
    public void retention(TrustedCallerBoundary.Caller c,UUID id,long version,boolean hold,Instant until){run(c,DOCUMENT_RETENTION,()->{repository.retention(access(c,DOCUMENT_RETENTION),id,version,hold,until);return null;});}
    public int cleanup(TrustedCallerBoundary.Caller c,UUID id,long version){return run(c,DOCUMENT_RETENTION,()->repository.cleanup(access(c,DOCUMENT_RETENTION),id,version,clock.instant()));}
    private EmbeddingProvider.Vector check(EmbeddingProvider.Vector v){if(!v.modelId().equals(provider.modelId())||v.dimension()!=provider.dimension())throw new IllegalArgumentException("Document embedding profile mismatch");return v.normalized();}
    private void source(ContextIdentity.Access a,String id){if(sources.resolve(a,id).origin()!=MemoryTypes.Origin.RETRIEVED_EVIDENCE)throw new SecurityException("Document source not allowlisted evidence");}
    static String render(Evidence e){var c=e.citation();return StructuredValues.json(Map.of("citation",c.marker(),"documentId",c.documentId().toString(),"sourceId",c.sourceId(),"version",c.version(),"chunkId",c.chunkId().toString(),"location",Map.of("kind",c.location().kind(),"ordinal",c.location().ordinal(),"section",c.location().section()),"contentHash",c.contentHash(),"authority","UNTRUSTED_DOCUMENT_EVIDENCE","text",e.text()));}
}
