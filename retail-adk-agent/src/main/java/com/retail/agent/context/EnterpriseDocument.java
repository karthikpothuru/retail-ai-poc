package com.retail.agent.context;

import java.time.Instant;
import java.util.*;
import static com.retail.agent.context.MemoryTypes.Sensitivity;

/** Separate document knowledge domain. No document is an approved memory or trusted instruction. */
public final class EnterpriseDocument {
    private EnterpriseDocument(){}
    public enum Status { ACTIVE, REVOKED, DELETED }
    public record Head(UUID id,String tenant,String owner,String sourceId,String sourceSystem,long version,long aclVersion,
                       Sensitivity classification,Status status,Instant ingestedAt,Instant expiresAt,boolean legalHold,Instant retainUntil){
        public Head{Objects.requireNonNull(id);ContextIdentity.identifier(tenant);ContextIdentity.identifier(owner);ContextIdentity.identifier(sourceId);ContextIdentity.identifier(sourceSystem);if(version<1||aclVersion<1)throw new IllegalArgumentException("Invalid document revision");Objects.requireNonNull(classification);Objects.requireNonNull(status);Objects.requireNonNull(ingestedAt);}
    }
    public record Draft(UUID id,long expectedVersion,String sourceId,String sourceSystem,String mime,Sensitivity classification,
                        Set<String> readers,Instant sourceModifiedAt,Instant expiresAt){
        public Draft{Objects.requireNonNull(id);if(expectedVersion<0)throw new IllegalArgumentException("Invalid expected version");ContextIdentity.identifier(sourceId);ContextIdentity.identifier(sourceSystem);Objects.requireNonNull(mime);Objects.requireNonNull(classification);readers=Set.copyOf(readers);if(readers.size()>100)throw new IllegalArgumentException("ACL too large");readers.forEach(ContextIdentity::identifier);Objects.requireNonNull(sourceModifiedAt);}
    }
    public record Location(String kind,int ordinal,String section){
        public Location{if(!Set.of("page","paragraph","section").contains(kind)||ordinal<1)throw new IllegalArgumentException("Invalid document location");section=section==null?"":section;if(section.length()>200)throw new IllegalArgumentException("Section too long");}
    }
    public record Segment(Location location,String text){public Segment{Objects.requireNonNull(location);Objects.requireNonNull(text);}}
    public record Extracted(String mime,String contentHash,List<Segment> segments){public Extracted{segments=List.copyOf(segments);if(!contentHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid document hash");}}
    public record Chunk(UUID id,int position,Location location,int start,int end,String text,String textHash){
        public Chunk{Objects.requireNonNull(id);Objects.requireNonNull(location);if(position<0||start<0||end<=start||text.length()!=end-start||!textHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid document chunk");}
    }
    public record Indexed(Chunk chunk,EmbeddingProvider.Vector vector,int embeddingVersion){public Indexed{Objects.requireNonNull(chunk);Objects.requireNonNull(vector);if(embeddingVersion!=1)throw new IllegalArgumentException("Unknown embedding generation version");}}
    public record Version(Head head,String mime,String contentHash,Instant sourceModifiedAt,List<Indexed> chunks,Set<String> readers){
        public Version{Objects.requireNonNull(head);Objects.requireNonNull(sourceModifiedAt);chunks=List.copyOf(chunks);readers=Set.copyOf(readers);if(chunks.isEmpty()||chunks.size()>1000||!contentHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid indexed document");}
    }
    public record Citation(UUID documentId,String sourceId,long version,UUID chunkId,Location location,int start,int end,String contentHash){
        public Citation{Objects.requireNonNull(documentId);Objects.requireNonNull(chunkId);ContextIdentity.identifier(sourceId);if(version<1||start<0||end<=start||!contentHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid citation");Objects.requireNonNull(location);}
        public String marker(){return "[DOC:"+documentId+":v"+version+":"+chunkId+"]";}
    }
    public record Evidence(Citation citation,long aclVersion,String text,Sensitivity classification,String modelId,int dimensions,int embeddingVersion,double score){
        public Evidence{Objects.requireNonNull(citation);Objects.requireNonNull(text);Objects.requireNonNull(classification);if(aclVersion<1||dimensions<1||embeddingVersion!=1||!Double.isFinite(score)||score< -1.000001||score>1.000001)throw new IllegalArgumentException("Invalid document evidence");}
    }
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static String hash(String text){return hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
