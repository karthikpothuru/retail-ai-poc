package com.retail.agent.context;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.retail.agent.context.MemoryTypes.*;

/** Additive service: leaves MemoryRepository contracts and existing context/agent behavior unchanged. */
public final class SemanticMemoryRetrieval {
    public record Query(String text,String operation,Map<String,Object> metadata,int limit,double minimumCosine) {
        public Query {
            StructuredValues.content(text);ContextIdentity.identifier(operation);
            metadata=StructuredValues.metadata(metadata);
            if(limit<1||limit>100||!Double.isFinite(minimumCosine)||minimumCosine< -1||minimumCosine>1)
                throw new IllegalArgumentException("Invalid retrieval bounds");
        }
    }
    public record Match(MemoryRecord memory,String modelId,int dimension,double cosine) {}
    private final DataSource pool;
    private final MemoryRepository memories;
    private final EmbeddingProvider provider;
    private final Clock clock;
    private final ContextAuditLog audit;

    public SemanticMemoryRetrieval(DataSource pool,MemoryRepository memories,EmbeddingProvider provider,Clock clock,ContextAuditLog audit) {
        this.pool=java.util.Objects.requireNonNull(pool);this.memories=java.util.Objects.requireNonNull(memories);
        this.provider=java.util.Objects.requireNonNull(provider);this.clock=java.util.Objects.requireNonNull(clock);this.audit=java.util.Objects.requireNonNull(audit);
        PostgresTransactions.requireRole(pool,"retail_memory_app");
    }

    public boolean index(ContextIdentity.Access access,UUID id,long expectedVersion) {
        MemoryWritePolicy.authorizeWrite(access);
        var memory=memories.read(access,id).orElseThrow(()->new SecurityException("Memory not available for indexing"));
        if(memory.version()!=expectedVersion)throw new java.util.ConcurrentModificationException("Memory changed before embedding");
        if(!eligible(memory)||!canIndex(access,memory))throw new SecurityException("Only authorized verified facts/approved procedures can be indexed");
        if(memory.type()==Type.PROCEDURAL)validateOperations(memory.metadata());
        var vector=check(provider.embedDocument(memory.content())); // network outside the database transaction
        return transaction(access,"EMBEDDING_INDEX",connection->{
            try(var lock=connection.prepareStatement("SELECT current_version FROM retail_memory.memory_heads WHERE memory_id=? FOR UPDATE")) {
                lock.setObject(1,id);
                try(var rows=lock.executeQuery()) {
                    if(!rows.next())throw new SecurityException("Memory no longer accessible");
                    if(rows.getLong(1)!=expectedVersion)throw new java.util.ConcurrentModificationException("Memory changed during embedding");
                }
            }
            try(var insert=connection.prepareStatement("INSERT INTO retail_memory.memory_embeddings(tenant_id,user_id,memory_id,memory_version,session_id,task_id,sensitivity,model_id,dimension,embedding) VALUES (?,?,?,?,?,?,?,?,?,?::public.vector) ON CONFLICT DO NOTHING")) {
                insert.setString(1,memory.scope().tenantId());insert.setString(2,memory.scope().userId());insert.setObject(3,id);insert.setLong(4,expectedVersion);
                insert.setString(5,memory.scope().sessionId());insert.setString(6,memory.scope().taskId());insert.setInt(7,memory.sensitivity().ordinal());
                insert.setString(8,vector.modelId());insert.setInt(9,vector.dimension());insert.setString(10,vector.sqlLiteral());
                int count=insert.executeUpdate();
                PostgresMemoryRepository.durableAudit(connection,access,"EMBEDDING_INDEX",id,expectedVersion,count);
                return count==1;
            }
        });
    }

    public List<Match> search(ContextIdentity.Access access,Query query) {
        access.authorize();
        var vector=check(provider.embedQuery(query.text()));
        return transaction(access,"SEMANTIC_SEARCH",connection->{
            // MATERIALIZED makes the dimension/model/authorization/metadata restrictions precede distance evaluation.
            String sql="""
                    WITH eligible AS MATERIALIZED (
                      SELECT v.*,e.embedding FROM retail_memory.memory_embeddings e
                      JOIN retail_memory.memory_heads h ON (h.tenant_id,h.user_id,h.memory_id,h.current_version)=
                          (e.tenant_id,e.user_id,e.memory_id,e.memory_version)
                      JOIN retail_memory.memory_versions v ON (v.tenant_id,v.user_id,v.memory_id,v.version)=
                          (e.tenant_id,e.user_id,e.memory_id,e.memory_version)
                      WHERE e.model_id=? AND e.dimension=? AND e.validity='VALID'
                        AND h.lifecycle='ACTIVE' AND v.lifecycle='ACTIVE'
                        AND (v.expires_at IS NULL OR (v.expires_at>? AND v.expires_at>statement_timestamp()))
                        AND v.metadata @> ?::jsonb
                        AND ((v.memory_type='SEMANTIC' AND v.verification='VERIFIED' AND v.source_origin IN ('KNOWLEDGE','TOOL_RESULT'))
                          OR (v.memory_type='PROCEDURAL' AND v.verification='APPROVED' AND v.source_origin='PROCEDURE'
                              AND jsonb_typeof(v.metadata->'operations')='array' AND (v.metadata->'operations') ?? ?))
                    ), scored AS (
                      SELECT eligible.*,1-(embedding OPERATOR(public.<=>) ?::public.vector) AS cosine FROM eligible
                    ), deduplicated AS (
                      SELECT scored.*,row_number() OVER(PARTITION BY memory_type,trim(regexp_replace(lower(content),'\\s+',' ','g'))
                        ORDER BY cosine DESC,updated_at DESC,memory_id) AS duplicate_rank FROM scored
                    ) SELECT * FROM deduplicated WHERE duplicate_rank=1 AND cosine>=?
                      ORDER BY cosine DESC,updated_at DESC,memory_id LIMIT ?
                    """;
            List<Match> matches=new ArrayList<>();
            try(var statement=connection.prepareStatement(sql)) {
                statement.setString(1,vector.modelId());statement.setInt(2,vector.dimension());
                statement.setTimestamp(3,java.sql.Timestamp.from(clock.instant()));statement.setString(4,PostgresTransactions.JSON.writeValueAsString(query.metadata()));
                statement.setString(5,query.operation());statement.setString(6,vector.sqlLiteral());statement.setDouble(7,query.minimumCosine());statement.setInt(8,query.limit());
                try(var rows=statement.executeQuery()) {
                    while(rows.next())matches.add(new Match(PostgresMemoryRepository.decode(rows),vector.modelId(),vector.dimension(),rows.getDouble("cosine")));
                }
            }
            PostgresMemoryRepository.durableAudit(connection,access,"SEMANTIC_SEARCH",null,null,matches.size());
            return List.copyOf(matches);
        });
    }

    public int invalidateExpired(ContextIdentity.Access access) {
        MemoryWritePolicy.authorizeWrite(access);
        return transaction(access,"EMBEDDING_EXPIRE",connection->{
            try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT retail_memory.expire_embeddings()")) {
                result.next();int count=result.getInt(1);
                PostgresMemoryRepository.durableAudit(connection,access,"EMBEDDING_EXPIRE",null,null,count);return count;
            }
        });
    }

    private EmbeddingProvider.Vector check(EmbeddingProvider.Vector vector) {
        if(!vector.modelId().equals(provider.modelId())||vector.dimension()!=provider.dimension())
            throw new IllegalArgumentException("Provider returned wrong model or dimension");
        return vector.normalized();
    }
    private static boolean eligible(MemoryRecord memory) {
        return memory.lifecycle()==Lifecycle.ACTIVE && ((memory.type()==Type.SEMANTIC&&memory.verification()==Verification.VERIFIED)
                ||(memory.type()==Type.PROCEDURAL&&memory.verification()==Verification.APPROVED));
    }
    private static boolean canIndex(ContextIdentity.Access access,MemoryRecord memory) {
        return access.identity().capabilities().contains(memory.type()==Type.SEMANTIC?Capability.VERIFY_SEMANTIC:Capability.APPROVE_PROCEDURE);
    }
    static void validateOperations(Map<String,Object> metadata) {
        if(!(metadata.get("operations") instanceof List<?> operations)||operations.isEmpty())throw new IllegalArgumentException("Procedure requires explicit operation applicability");
        operations.forEach(operation->{if(!(operation instanceof String name))throw new IllegalArgumentException("Invalid operation");ContextIdentity.identifier(name);});
    }
    private <T>T transaction(ContextIdentity.Access access,String action,PostgresTransactions.Work<T> work) {
        try {T result=PostgresTransactions.inScope(pool,access,work);audit.record(access,action,"SUCCESS",null,0);return result;}
        catch(RuntimeException failure){audit.record(access,action,"REJECTED",null,0);throw failure;}
    }
}
