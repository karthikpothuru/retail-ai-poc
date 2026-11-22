package com.retail.agent.context;

import java.util.*;
import java.util.function.Supplier;
import static com.retail.agent.context.MemoryTypes.*;
import static com.retail.agent.context.TrustedCallerBoundary.Operation.*;

/** Host-facing API. Internal repositories and provisioning capabilities must not be exposed as tools. */
public final class GovernedMemoryService {
    public record View(UUID id,long version,Type type,String content,Map<String,Object> metadata,
                       boolean redacted,Set<MemoryPoisoningDetector.Signal> signals,String trust) {}
    private final TrustedCallerBoundary boundary;
    private final MemoryRepository repository;
    private final SourceRegistry sources;
    private final GovernanceAudit audit;
    private final MemoryPoisoningDetector detector=new MemoryPoisoningDetector();
    public GovernedMemoryService(TrustedCallerBoundary boundary,MemoryRepository repository,SourceRegistry sources,GovernanceAudit audit){
        this.boundary=Objects.requireNonNull(boundary);this.repository=Objects.requireNonNull(repository);this.sources=Objects.requireNonNull(sources);this.audit=Objects.requireNonNull(audit);
    }
    public TrustedCallerBoundary.Caller authenticate(String token,String session,String task){
        try{return boundary.authenticate(token,session,task);}catch(RuntimeException failure){
            audit.independent(GovernanceAudit.event(null,false,"AUTHENTICATE","DENIED","INVALID_IDENTITY",null,null),null);throw failure;
        }
    }
    /** Preserves underlying SQL ranking, eligibility and metadata predicates; returns delivery-safe views. */
    public List<View> semanticSearch(TrustedCallerBoundary.Caller c,SemanticMemoryRetrieval retrieval,SemanticMemoryRetrieval.Query query){
        return run(c,READ,null,()->retrieval.search(c.access(),query).stream().map(m->view(c,m.memory())).toList());
    }
    ContextIdentity.Access runtimeAccess(TrustedCallerBoundary.Caller caller){return run(caller,READ,null,caller==null?()->null:caller::access);}
    void runtimeDenial(TrustedCallerBoundary.Caller caller,String reason){boolean known=boundary.authentic(caller);audit.independent(GovernanceAudit.event(caller,known,"ADK_RUNTIME","DENIED",reason,null,null),known?caller.access():null);}
    void runtimeToolSource(TrustedCallerBoundary.Caller caller,String sourceId){var access=runtimeAccess(caller);if(sources.resolve(access,sourceId).origin()!=Origin.TOOL_RESULT)throw new SecurityException("MCP source must be registered tool evidence");}
    record RuntimeSelection(List<MemoryRecord> memories,List<AssembledContext.Omission> omissions){}
    /** Package-private projection for the trusted runtime, never a tool/public raw-memory endpoint. */
    RuntimeSelection runtimeSelection(TrustedCallerBoundary.Caller c,String operation,String goal,SemanticMemoryRetrieval semantic){
        return run(c,READ,null,()->{
            var selected=new LinkedHashMap<UUID,MemoryRecord>();var omissions=new ArrayList<AssembledContext.Omission>();
            var candidates=new ArrayList<>(repository.search(c.access(),new MemoryRepository.Search(Set.of(),"",1000)));
            boolean authoritative=candidates.stream().anyMatch(r->r.type()==Type.SEMANTIC&&r.verification()==Verification.VERIFIED||r.type()==Type.PROCEDURAL&&r.verification()==Verification.APPROVED);
            Set<UUID> matches=semantic==null?null:authoritative?semantic.search(c.access(),new SemanticMemoryRetrieval.Query(goal,operation,Map.of(),100,-1)).stream().map(m->m.memory().id()).collect(java.util.stream.Collectors.toSet()):Set.of();
            if(candidates.size()==1000)omissions.add(new AssembledContext.Omission("runtime:retrieval","CANDIDATE_LIMIT_POSSIBLE"));
            for(var r:candidates){
                String reason=null;
                if(r.type()==Type.SEMANTIC&&r.verification()!=Verification.VERIFIED||r.type()==Type.PROCEDURAL&&r.verification()!=Verification.APPROVED)reason="UNVERIFIED_OR_UNAPPROVED";
                else if(r.type()==Type.PROCEDURAL&&(!(r.metadata().get("operations") instanceof List<?> operations)||!operations.contains(operation)))reason="OPERATION_NOT_APPLICABLE";
                else if(matches!=null&&(r.type()==Type.SEMANTIC||r.type()==Type.PROCEDURAL)&&!matches.contains(r.id()))reason="NOT_IN_SEMANTIC_RESULTS";
                if(reason==null){
                    var current=repository.read(c.access(),r.id());
                    if(current.isEmpty()||current.get().version()!=r.version())reason="STALE_BEFORE_ASSEMBLY";
                    else if(view(c,r).redacted())reason="CLASSIFICATION_REDACTED";
                }
                if(reason!=null)omissions.add(new AssembledContext.Omission("memory:"+r.id(),reason));else selected.put(r.id(),r);
            }
            return new RuntimeSelection(List.copyOf(selected.values()),List.copyOf(omissions));
        });
    }
    public Optional<View> read(TrustedCallerBoundary.Caller c,UUID id){return run(c,READ,null,()->repository.read(c.access(),id).map(r->view(c,r)));}
    public List<View> search(TrustedCallerBoundary.Caller c,MemoryRepository.Search query){return run(c,READ,null,()->repository.search(c.access(),query).stream().map(r->view(c,r)).toList());}
    List<View> searchExactScope(TrustedCallerBoundary.Caller c, MemoryRepository.Search query) {
        return run(c, READ, null, () -> {
            var scope = ContextIdentity.Scope.from(c.access());
            return repository.search(c.access(), query).stream().filter(r -> scope.equals(r.scope()))
                    .map(r -> view(c, r)).toList();
        });
    }
    public View create(TrustedCallerBoundary.Caller c,MemoryRecord.Draft draft){return run(c,CREATE,"MEMORY_CREATE",()->{
        approve(c,draft);return view(c,repository.create(c.access(),draft));});}
    public View update(TrustedCallerBoundary.Caller c,UUID id,long version,MemoryRecord.Draft draft){return change(c,id,version,draft,UPDATE);}
    public View verify(TrustedCallerBoundary.Caller c,UUID id,long version,MemoryRecord.Draft draft){
        return change(c,id,version,draft,VERIFY);
    }
    public View approveProcedure(TrustedCallerBoundary.Caller c,UUID id,long version,MemoryRecord.Draft draft){
        return change(c,id,version,draft,APPROVE);
    }
    private View change(TrustedCallerBoundary.Caller c,UUID id,long version,MemoryRecord.Draft draft,TrustedCallerBoundary.Operation op){return run(c,op,"MEMORY_UPDATE",()->{
        if(op==VERIFY&&(draft.type()!=Type.SEMANTIC||draft.verification()!=Verification.VERIFIED))throw new IllegalArgumentException("Expected verified semantic draft");
        if(op==APPROVE&&(draft.type()!=Type.PROCEDURAL||draft.verification()!=Verification.APPROVED))throw new IllegalArgumentException("Expected approved procedure draft");
        var old=repository.read(c.access(),id).orElseThrow(()->new SecurityException("Memory inaccessible"));
        if(op==UPDATE&&old.verification()!=draft.verification())throw new SecurityException("Explicit verification operation required");
        if(draft.sensitivity().ordinal()<old.sensitivity().ordinal())throw new SecurityException("Declassification requires external review");
        authoritative(c,old);approve(c,draft);return view(c,repository.update(c.access(),id,version,draft));});}
    public View revoke(TrustedCallerBoundary.Caller c,UUID id,long version){return run(c,REVOKE,"MEMORY_REVOKE",()->{
        authoritative(c,repository.read(c.access(),id).orElseThrow(()->new SecurityException("Memory inaccessible")));return view(c,repository.revoke(c.access(),id,version));});}
    public void delete(TrustedCallerBoundary.Caller c,UUID id,long version){run(c,DELETE,"MEMORY_DELETE",()->{
        // Repository enforces legal holds, retention and optimistic version in the mutation transaction.
        var visible=repository.read(c.access(),id);
        if(visible.isPresent())authoritative(c,visible.get());
        else {
            // The preserved read contract hides revoked/expired records. Do not infer candidate trust
            // from an empty read: require both authority grants before deleting a hidden revision.
            boundary.require(c,VERIFY);boundary.require(c,APPROVE);
        }
        repository.delete(c.access(),id,version);return null;});}
    private void authoritative(TrustedCallerBoundary.Caller c,MemoryRecord r){
        if(r.verification()==Verification.VERIFIED)boundary.require(c,VERIFY);
        if(r.verification()==Verification.APPROVED)boundary.require(c,APPROVE);
    }
    private void approve(TrustedCallerBoundary.Caller c,MemoryRecord.Draft d){
        sources.resolve(c.access(),d.sourceId());
        if(d.metadata().keySet().stream().map(k->k.toLowerCase(Locale.ROOT)).anyMatch(k->Set.of("policy","verification","approved","tenantid","userid","systemprompt","instructions").contains(k)))
            throw new SecurityException("Governance claims cannot be set through metadata");
        // Generated/derived memory requires a lineage-aware lifecycle, intentionally unavailable here.
        if(d.metadata().keySet().stream().map(k->k.toLowerCase(Locale.ROOT)).anyMatch(k->Set.of("derivedfrom","references","summaryof").contains(k)))
            throw new SecurityException("Derived memory registration requires external lineage review");
        if(d.verification()!=Verification.CANDIDATE){
            boundary.require(c,d.verification()==Verification.VERIFIED?VERIFY:APPROVE);
            if(!detector.inspect(d.content()+" "+StructuredValues.json(d.metadata())).isEmpty())throw new SecurityException("Suspicious authoritative content requires external review");
        }
    }
    private View view(TrustedCallerBoundary.Caller c,MemoryRecord r){
        if(!sources.resolve(c.access(),r.provenance().id()).equals(r.provenance()))throw new SecurityException("Provenance changed");
        boolean redact=r.sensitivity().ordinal()>c.disclosure().ordinal();
        return new View(r.id(),r.version(),r.type(),redact?"[REDACTED]":r.content(),redact?Map.of():r.metadata(),redact,redact?Set.of():Set.copyOf(detector.inspect(r.content()+" "+StructuredValues.json(r.metadata()))),"UNTRUSTED_REFERENCE_DATA");
    }
    private <T>T run(TrustedCallerBoundary.Caller c,TrustedCallerBoundary.Operation op,String action,Supplier<T> work){
        try{
            boundary.require(c,op);
            if(action==null)return work.get();
            try(var scope=new GovernanceAuditScope(c,op.name(),"AUTHORIZED",action)){return work.get();}
        }catch(RuntimeException failure){
            boolean authentic=boundary.authentic(c);
            audit.independent(GovernanceAudit.event(c,authentic,op.name(),failure instanceof SecurityException?"DENIED":"ROLLED_BACK",failure instanceof SecurityException?"ACCESS_POLICY":"OPERATION_FAILED",null,null),authentic?c.access():null);
            throw failure;
        }
    }
}
