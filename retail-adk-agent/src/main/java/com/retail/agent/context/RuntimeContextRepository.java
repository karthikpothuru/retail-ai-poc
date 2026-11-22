package com.retail.agent.context;

import java.util.*;

/** Per-turn immutable authorized projection; revalidates current versions at assembly time. */
final class RuntimeContextRepository implements MemoryRepository {
    private final GovernedMemoryService governance;
    private final TrustedCallerBoundary.Caller caller;
    private final Map<UUID,MemoryRecord> memories;
    RuntimeContextRepository(GovernedMemoryService governance,TrustedCallerBoundary.Caller caller,List<MemoryRecord> selected){
        this.governance=governance;this.caller=caller;var map=new LinkedHashMap<UUID,MemoryRecord>();selected.forEach(r->map.put(r.id(),r));memories=Map.copyOf(map);
    }
    private void require(ContextIdentity.Access access){if(!governance.runtimeAccess(caller).equals(access))throw new SecurityException("Runtime scope mismatch");}
    public Optional<MemoryRecord> read(ContextIdentity.Access access,UUID id){require(access);var r=memories.get(id);if(r==null)return Optional.empty();
        return governance.read(caller,id).filter(v->!v.redacted()&&v.version()==r.version()).map(v->r);}
    public List<MemoryRecord> search(ContextIdentity.Access access,Search query){require(access);return memories.values().stream().sorted(Comparator.comparing(r->r.id().toString())).toList();}
    public MemoryRecord create(ContextIdentity.Access a,MemoryRecord.Draft d){throw new UnsupportedOperationException("Read-only runtime snapshot");}
    public MemoryRecord update(ContextIdentity.Access a,UUID id,long v,MemoryRecord.Draft d){throw new UnsupportedOperationException("Read-only runtime snapshot");}
    public MemoryRecord revoke(ContextIdentity.Access a,UUID id,long v){throw new UnsupportedOperationException("Read-only runtime snapshot");}
    public void delete(ContextIdentity.Access a,UUID id,long v){throw new UnsupportedOperationException("Read-only runtime snapshot");}
}
