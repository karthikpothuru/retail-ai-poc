package com.retail.agent.context;

import java.time.Instant;
import java.util.*;
import static com.retail.agent.context.EnterpriseDocument.*;

/** Offline deterministic implementation of the document contract, including explicit ACL/hold behavior. */
public final class InMemoryDocumentRepository implements DocumentRepository {
    private final Map<String,NavigableMap<Long,Version>> versions=new HashMap<>();
    private String key(ContextIdentity.Access a,UUID id){a.authorize();return a.identity().tenantId()+":"+id;}
    private Version latest(ContextIdentity.Access a,UUID id){var map=versions.get(key(a,id));if(map==null)throw new SecurityException("Document inaccessible");return map.lastEntry().getValue();}
    private boolean visible(ContextIdentity.Access a,Version v,Instant now){var h=v.head();return h.tenant().equals(a.identity().tenantId())&&(h.owner().equals(a.identity().userId())||v.readers().contains(a.identity().userId()))&&h.classification().ordinal()<=a.identity().clearance().ordinal()&&h.status()==Status.ACTIVE&&(h.expiresAt()==null||h.expiresAt().isAfter(now));}
    private Version owner(ContextIdentity.Access a,UUID id){var v=latest(a,id);if(!v.head().owner().equals(a.identity().userId())||v.head().classification().ordinal()>a.identity().clearance().ordinal())throw new SecurityException("Document owner required");return v;}
    private void replace(ContextIdentity.Access a,Version old,Head h,Set<String> readers){versions.get(key(a,h.id())).put(h.version(),new Version(h,old.mime(),old.contentHash(),old.sourceModifiedAt(),old.chunks(),readers));}
    public synchronized Version activate(ContextIdentity.Access a,Draft d,Extracted e,List<Indexed> chunks,Instant now){
        Version old=null;if(d.expectedVersion()>0){old=owner(a,d.id());if(old.head().version()!=d.expectedVersion())throw new ConcurrentModificationException();if(old.head().status()!=Status.ACTIVE||!old.head().sourceId().equals(d.sourceId())||!old.head().sourceSystem().equals(d.sourceSystem()))throw new SecurityException("Immutable source/lifecycle mismatch");}
        else if(versions.containsKey(key(a,d.id())))throw new ConcurrentModificationException();
        var h=new Head(d.id(),a.identity().tenantId(),a.identity().userId(),d.sourceId(),d.sourceSystem(),d.expectedVersion()+1,old==null?1:old.head().aclVersion(),d.classification(),Status.ACTIVE,now,d.expiresAt(),old!=null&&old.head().legalHold(),old==null?null:old.head().retainUntil());
        var next=new Version(h,d.mime(),e.contentHash(),d.sourceModifiedAt(),chunks,old==null?d.readers():old.readers());versions.computeIfAbsent(key(a,d.id()),k->new TreeMap<>()).put(h.version(),next);return next;
    }
    public synchronized List<Evidence> search(ContextIdentity.Access a,EmbeddingProvider.Vector query,int limit,double threshold,Instant now,UUID retrievalOperation){
        return search(a,query,limit,threshold,now,retrievalOperation,null);
    }
    public synchronized List<Evidence> search(ContextIdentity.Access a,EmbeddingProvider.Vector query,int limit,double threshold,Instant now,UUID retrievalOperation,Set<UUID> documentIds){
        a.authorize();var results=new ArrayList<Evidence>();for(var map:versions.values()){var v=map.lastEntry().getValue();if(!visible(a,v,now)||documentIds!=null&&!documentIds.contains(v.head().id()))continue;
            for(var c:v.chunks())if(c.vector().modelId().equals(query.modelId())&&c.vector().dimension()==query.dimension()){
                double score=0;var x=c.vector().normalized();for(int i=0;i<query.dimension();i++)score+=(double)x.values().get(i)*query.values().get(i);
                if(score>=threshold)results.add(new Evidence(new Citation(v.head().id(),v.head().sourceId(),v.head().version(),c.chunk().id(),c.chunk().location(),c.chunk().start(),c.chunk().end(),v.contentHash()),v.head().aclVersion(),c.chunk().text(),v.head().classification(),query.modelId(),query.dimension(),c.embeddingVersion(),Math.max(-1,Math.min(1,score))));
            }
        }results.sort(Comparator.comparingDouble(Evidence::score).reversed().thenComparing(e->e.citation().documentId().toString()).thenComparing(e->e.citation().chunkId().toString()));return List.copyOf(results.subList(0,Math.min(limit,results.size())));
    }
    public synchronized boolean current(ContextIdentity.Access a,Evidence e,Instant now){try{var v=latest(a,e.citation().documentId());return visible(a,v,now)&&v.head().version()==e.citation().version()&&v.head().aclVersion()==e.aclVersion()&&v.head().classification()==e.classification()&&v.chunks().stream().anyMatch(c->new Citation(v.head().id(),v.head().sourceId(),v.head().version(),c.chunk().id(),c.chunk().location(),c.chunk().start(),c.chunk().end(),v.contentHash()).equals(e.citation())&&c.chunk().text().equals(e.text())&&c.vector().modelId().equals(e.modelId())&&c.vector().dimension()==e.dimensions()&&c.embeddingVersion()==e.embeddingVersion());}catch(SecurityException hidden){return false;}}
    public synchronized Head head(ContextIdentity.Access a,UUID id){return owner(a,id).head();}
    public synchronized Head lifecycle(ContextIdentity.Access a,UUID id,long version,Status state){var v=owner(a,id);if(v.head().version()!=version)throw new ConcurrentModificationException();if(v.head().status()==Status.DELETED||state==Status.ACTIVE)throw new SecurityException("Cannot reactivate document");var h=v.head();var next=new Head(id,h.tenant(),h.owner(),h.sourceId(),h.sourceSystem(),version,h.aclVersion()+1,h.classification(),state,h.ingestedAt(),h.expiresAt(),h.legalHold(),h.retainUntil());replace(a,v,next,v.readers());return next;}
    public synchronized Head acl(ContextIdentity.Access a,UUID id,long expected,Set<String> readers){var v=owner(a,id);var h=v.head();if(h.aclVersion()!=expected)throw new ConcurrentModificationException();var next=new Head(id,h.tenant(),h.owner(),h.sourceId(),h.sourceSystem(),h.version(),expected+1,h.classification(),h.status(),h.ingestedAt(),h.expiresAt(),h.legalHold(),h.retainUntil());replace(a,v,next,Set.copyOf(readers));return next;}
    public synchronized void retention(ContextIdentity.Access a,UUID id,long version,boolean hold,Instant until){var v=owner(a,id);var h=v.head();if(h.version()!=version)throw new ConcurrentModificationException();replace(a,v,new Head(id,h.tenant(),h.owner(),h.sourceId(),h.sourceSystem(),version,h.aclVersion(),h.classification(),h.status(),h.ingestedAt(),h.expiresAt(),hold,until),v.readers());}
    public synchronized int cleanup(ContextIdentity.Access a,UUID id,long version,Instant now){var v=owner(a,id);var h=v.head();if(h.version()!=version)throw new ConcurrentModificationException();if(h.legalHold()||h.retainUntil()!=null&&h.retainUntil().isAfter(now))throw new SecurityException("Document retention protection");var map=versions.get(key(a,id));int count=map.size();if(h.status()==Status.DELETED){versions.remove(key(a,id));return count;}map.headMap(version,false).clear();return count-map.size();}
}
