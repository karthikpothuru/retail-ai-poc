package com.retail.agent.context;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.retail.agent.context.EnterpriseDocument.*;

/** Dedicated document pools: reader cannot ingest or release legal holds. All retrieval predicates run in SQL/RLS. */
public final class PostgresDocumentRepository implements DocumentRepository {
    private final DataSource reader,writer,retention;
    public PostgresDocumentRepository(DataSource reader,DataSource writer,DataSource retention){
        this.reader=Objects.requireNonNull(reader);this.writer=writer;this.retention=retention;
        role(reader,"retail_document_app");if(writer!=null)role(writer,"retail_document_ingest");if(retention!=null)role(retention,"retail_document_retention");
    }
    private static void role(DataSource pool,String role){
        PostgresTransactions.requireRole(pool,role);
        try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT pg_has_role(current_user,'retail_document_ingest','MEMBER'),pg_has_role(current_user,'retail_document_retention','MEMBER'),pg_has_role(current_user,'retail_memory_app','MEMBER'),pg_has_role(current_user,'retail_memory_retention','MEMBER'),pg_has_role(current_user,'retail_document_app','MEMBER')")){
            r.next();if(r.getBoolean(3)||r.getBoolean(4)||role.equals("retail_document_app")&&(r.getBoolean(1)||r.getBoolean(2))||role.equals("retail_document_ingest")&&r.getBoolean(2)||role.equals("retail_document_retention")&&(r.getBoolean(1)||r.getBoolean(5)))throw new SecurityException("Document database roles must be separate");
        }catch(SQLException e){throw new IllegalStateException("Document role validation failed");}
    }
    private <T>T tx(DataSource pool,ContextIdentity.Access a,PostgresTransactions.Work<T> work){if(pool==null)throw new SecurityException("Document operation pool not configured");try{return PostgresTransactions.inScope(pool,a,work);}catch(SecurityException|ConcurrentModificationException expected){throw expected;}catch(RuntimeException failure){throw new IllegalStateException("Document database operation failed; content diagnostics omitted");}}
    private static void id(PreparedStatement s,int at,ContextIdentity.Access a,UUID id)throws SQLException{s.setString(at,a.identity().tenantId());s.setObject(at+1,id);}
    private static Timestamp timestamp(Instant time){return time==null?null:Timestamp.from(time);}
    private static Head decode(ResultSet r)throws SQLException{return new Head(r.getObject("document_id",UUID.class),r.getString("tenant_id"),r.getString("owner_user_id"),r.getString("source_id"),r.getString("source_system"),r.getLong("current_version"),r.getLong("acl_version"),MemoryTypes.Sensitivity.values()[r.getInt("classification")],Status.valueOf(r.getString("status")),r.getTimestamp("ingested_at").toInstant(),r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant(),r.getBoolean("legal_hold"),r.getTimestamp("retain_until")==null?null:r.getTimestamp("retain_until").toInstant());}
    private static Head owner(Connection c,ContextIdentity.Access a,UUID document,boolean lock)throws Exception{
        try(var s=c.prepareStatement("SELECT * FROM retail_memory.documents WHERE tenant_id=? AND document_id=? AND owner_user_id=?"+(lock?" FOR UPDATE":""))){id(s,1,a,document);s.setString(3,a.identity().userId());try(var r=s.executeQuery()){if(!r.next())throw new SecurityException("Document inaccessible");return decode(r);}}
    }
    private static void audit(Connection c,ContextIdentity.Access a,String action,UUID document,Long version,int count)throws Exception{
        audit(c,a,action,document,version,count,UUID.randomUUID());
    }
    private static void audit(Connection c,ContextIdentity.Access a,String action,UUID document,Long version,int count,UUID eventId)throws Exception{
        try(var s=c.prepareStatement("INSERT INTO retail_memory.document_audit(event_id,tenant_id,user_id,action,document_id,version,affected_count) VALUES (?,?,?,?,?,?,?)")){s.setObject(1,eventId);s.setString(2,a.identity().tenantId());s.setString(3,a.identity().userId());s.setString(4,action);s.setObject(5,document);s.setObject(6,version);s.setInt(7,count);s.executeUpdate();}
    }
    public Version activate(ContextIdentity.Access a,Draft d,Extracted e,List<Indexed> chunks,Instant now){return tx(writer,a,c->{
        Head old=null;
        if(d.expectedVersion()>0){old=owner(c,a,d.id(),true);if(old.version()!=d.expectedVersion())throw new ConcurrentModificationException("Document version conflict");if(old.status()!=Status.ACTIVE||!old.sourceId().equals(d.sourceId())||!old.sourceSystem().equals(d.sourceSystem()))throw new SecurityException("Document immutable source/lifecycle mismatch");}
        long version=d.expectedVersion()+1;
        if(old==null){try(var s=c.prepareStatement("INSERT INTO retail_memory.documents(tenant_id,document_id,owner_user_id,source_id,source_system,current_version,acl_version,classification,status,ingested_at,expires_at) VALUES (?,?,?,?,?,?,1,?,'ACTIVE',?,?)")){id(s,1,a,d.id());s.setString(3,a.identity().userId());s.setString(4,d.sourceId());s.setString(5,d.sourceSystem());s.setLong(6,version);s.setInt(7,d.classification().ordinal());s.setTimestamp(8,timestamp(now));s.setTimestamp(9,timestamp(d.expiresAt()));s.executeUpdate();}}
        try(var s=c.prepareStatement("INSERT INTO retail_memory.document_versions(tenant_id,document_id,version,mime,content_hash,source_modified_at,ingested_at,state) VALUES (?,?,?,?,?,?,?,'READY')")){id(s,1,a,d.id());s.setLong(3,version);s.setString(4,d.mime());s.setString(5,e.contentHash());s.setTimestamp(6,timestamp(d.sourceModifiedAt()));s.setTimestamp(7,timestamp(now));s.executeUpdate();}
        for(var indexed:chunks){var chunk=indexed.chunk();var vector=indexed.vector();
            try(var s=c.prepareStatement("INSERT INTO retail_memory.document_chunks(tenant_id,document_id,version,chunk_id,position,location_kind,location_ordinal,section,start_offset,end_offset,content,text_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")){
                id(s,1,a,d.id());s.setLong(3,version);s.setObject(4,chunk.id());s.setInt(5,chunk.position());s.setString(6,chunk.location().kind());s.setInt(7,chunk.location().ordinal());s.setString(8,chunk.location().section());s.setInt(9,chunk.start());s.setInt(10,chunk.end());s.setString(11,chunk.text());s.setString(12,chunk.textHash());s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO retail_memory.document_embeddings(tenant_id,document_id,version,chunk_id,model_id,dimension,embedding_version,embedding) VALUES (?,?,?,?,?,?,?,?::public.vector)")){
                id(s,1,a,d.id());s.setLong(3,version);s.setObject(4,chunk.id());s.setString(5,vector.modelId());s.setInt(6,vector.dimension());s.setInt(7,indexed.embeddingVersion());s.setString(8,vector.sqlLiteral());s.executeUpdate();}
        }
        if(old==null){writeAcl(c,a,d.id(),d.readers());}
        else{
            try(var s=c.prepareStatement("UPDATE retail_memory.document_versions SET state='SUPERSEDED' WHERE tenant_id=? AND document_id=? AND version=?")){id(s,1,a,d.id());s.setLong(3,old.version());s.executeUpdate();}
            try(var s=c.prepareStatement("UPDATE retail_memory.documents SET current_version=?,classification=?,ingested_at=?,expires_at=? WHERE tenant_id=? AND document_id=?")){s.setLong(1,version);s.setInt(2,d.classification().ordinal());s.setTimestamp(3,timestamp(now));s.setTimestamp(4,timestamp(d.expiresAt()));id(s,5,a,d.id());s.executeUpdate();}
        }
        var head=owner(c,a,d.id(),false);audit(c,a,"INGEST",d.id(),version,chunks.size());
        var readers=new HashSet<String>();try(var s=c.prepareStatement("SELECT principal_user_id FROM retail_memory.document_acls WHERE tenant_id=? AND document_id=?")){id(s,1,a,d.id());try(var r=s.executeQuery()){while(r.next())readers.add(r.getString(1));}}
        return new Version(head,d.mime(),e.contentHash(),d.sourceModifiedAt(),chunks,readers);
    });}
    private static void writeAcl(Connection c,ContextIdentity.Access a,UUID doc,Set<String> readers)throws Exception{for(String user:readers)try(var s=c.prepareStatement("INSERT INTO retail_memory.document_acls(tenant_id,document_id,principal_user_id) VALUES (?,?,?)")){id(s,1,a,doc);s.setString(3,user);s.executeUpdate();}}
    private static final String ELIGIBLE="""
        SELECT h.*,v.content_hash,c.chunk_id,c.location_kind,c.location_ordinal,c.section,c.start_offset,c.end_offset,c.content,e.model_id,e.dimension,e.embedding_version,e.embedding
        FROM retail_memory.documents h
        JOIN retail_memory.document_versions v ON (v.tenant_id,v.document_id,v.version)=(h.tenant_id,h.document_id,h.current_version)
        JOIN retail_memory.document_chunks c ON (c.tenant_id,c.document_id,c.version)=(v.tenant_id,v.document_id,v.version)
        JOIN retail_memory.document_embeddings e ON (e.tenant_id,e.document_id,e.version,e.chunk_id)=(c.tenant_id,c.document_id,c.version,c.chunk_id)
        WHERE h.status='ACTIVE' AND v.state='READY' AND e.valid
          AND (h.expires_at IS NULL OR h.expires_at>statement_timestamp())
          AND retail_memory.document_visible(h.tenant_id,h.document_id)
        """;
    private static Evidence evidence(ResultSet r,double score)throws SQLException{return new Evidence(new Citation(r.getObject("document_id",UUID.class),r.getString("source_id"),r.getLong("current_version"),r.getObject("chunk_id",UUID.class),new Location(r.getString("location_kind"),r.getInt("location_ordinal"),r.getString("section")),r.getInt("start_offset"),r.getInt("end_offset"),r.getString("content_hash")),r.getLong("acl_version"),r.getString("content"),MemoryTypes.Sensitivity.values()[r.getInt("classification")],r.getString("model_id"),r.getInt("dimension"),r.getInt("embedding_version"),Math.max(-1,Math.min(1,score)));}
    public List<Evidence> search(ContextIdentity.Access a,EmbeddingProvider.Vector query,int limit,double threshold,Instant now,UUID retrievalOperation){
        return search(a,query,limit,threshold,now,retrievalOperation,null);
    }
    public List<Evidence> search(ContextIdentity.Access a,EmbeddingProvider.Vector query,int limit,double threshold,Instant now,UUID retrievalOperation,Set<UUID> documentIds){return tx(reader,a,c->{
        String filter=documentIds==null?"":" AND h.document_id = ANY(?)";
        String sql="WITH eligible AS MATERIALIZED ("+ELIGIBLE+filter+" AND e.model_id=? AND e.dimension=? AND (h.expires_at IS NULL OR h.expires_at>?) ), scored AS (SELECT eligible.*,1-(embedding OPERATOR(public.<=>) ?::public.vector) AS score FROM eligible) SELECT * FROM scored WHERE score>=? ORDER BY score DESC,document_id,chunk_id LIMIT ?";
        return ranked(c,a,query,limit,threshold,now,retrievalOperation,documentIds,sql);
    });}
    private static List<Evidence> ranked(Connection connection,ContextIdentity.Access access,EmbeddingProvider.Vector query,
            int limit,double threshold,Instant now,UUID operation,Set<UUID> documentIds,String sql)throws Exception{
        var results=new ArrayList<Evidence>();
        try(var statement=connection.prepareStatement(sql)){
            int parameter=1;
            var ids=documentIds==null?null:connection.createArrayOf("uuid",documentIds.toArray(UUID[]::new));
            try{
                if(ids!=null)statement.setArray(parameter++,ids);
                statement.setString(parameter++,query.modelId());
                statement.setInt(parameter++,query.dimension());
                statement.setTimestamp(parameter++,timestamp(now));
                statement.setString(parameter++,query.sqlLiteral());
                statement.setDouble(parameter++,threshold);
                statement.setInt(parameter,limit);
                try(var rows=statement.executeQuery()){while(rows.next())results.add(evidence(rows,rows.getDouble("score")));}
            }finally{if(ids!=null)ids.free();}
        }
        audit(connection,access,"SEARCH",null,null,results.size(),operation);
        return List.copyOf(results);
    }
    public boolean current(ContextIdentity.Access a,Evidence expected,Instant now){return tx(reader,a,c->{
        var e=expected.citation();boolean matches=false;
        try(var s=c.prepareStatement(ELIGIBLE+" AND h.tenant_id=? AND h.document_id=? AND h.current_version=? AND c.chunk_id=? AND h.acl_version=? AND e.model_id=? AND e.dimension=? AND (h.expires_at IS NULL OR h.expires_at>?)")){
            id(s,1,a,e.documentId());s.setLong(3,e.version());s.setObject(4,e.chunkId());s.setLong(5,expected.aclVersion());s.setString(6,expected.modelId());s.setInt(7,expected.dimensions());s.setTimestamp(8,timestamp(now));
            try(var r=s.executeQuery()){if(r.next()){var found=evidence(r,expected.score());matches=found.citation().equals(e)&&found.text().equals(expected.text())&&found.classification()==expected.classification();}}
        }audit(c,a,"REVALIDATE",matches?e.documentId():null,matches?e.version():null,matches?1:0);return matches;
    });}
    public Head head(ContextIdentity.Access a,UUID id){return tx(reader,a,c->owner(c,a,id,false));}
    public Head lifecycle(ContextIdentity.Access a,UUID id,long version,Status state){return tx(writer,a,c->{
        var old=owner(c,a,id,true);if(old.version()!=version)throw new ConcurrentModificationException();if(state==Status.ACTIVE||old.status()==Status.DELETED)throw new SecurityException("Cannot reactivate document");
        try(var s=c.prepareStatement("UPDATE retail_memory.documents SET status=?,acl_version=acl_version+1 WHERE tenant_id=? AND document_id=?")){s.setString(1,state.name());id(s,2,a,id);s.executeUpdate();}audit(c,a,state.name(),id,version,1);return owner(c,a,id,false);
    });}
    public Head acl(ContextIdentity.Access a,UUID id,long expected,Set<String> readers){return tx(writer,a,c->{
        var old=owner(c,a,id,true);if(old.aclVersion()!=expected)throw new ConcurrentModificationException();
        try(var s=c.prepareStatement("DELETE FROM retail_memory.document_acls WHERE tenant_id=? AND document_id=?")){id(s,1,a,id);s.executeUpdate();}writeAcl(c,a,id,readers);
        try(var s=c.prepareStatement("UPDATE retail_memory.documents SET acl_version=acl_version+1 WHERE tenant_id=? AND document_id=?")){id(s,1,a,id);s.executeUpdate();}audit(c,a,"ACL",id,old.version(),1);return owner(c,a,id,false);
    });}
    public void retention(ContextIdentity.Access a,UUID id,long version,boolean hold,Instant until){tx(retention,a,c->{var old=owner(c,a,id,true);if(old.version()!=version)throw new ConcurrentModificationException();try(var s=c.prepareStatement("UPDATE retail_memory.documents SET legal_hold=?,retain_until=? WHERE tenant_id=? AND document_id=?")){s.setBoolean(1,hold);s.setTimestamp(2,timestamp(until));id(s,3,a,id);s.executeUpdate();}audit(c,a,"RETENTION",id,version,1);return null;});}
    public int cleanup(ContextIdentity.Access a,UUID id,long version,Instant now){return tx(retention,a,c->{var h=owner(c,a,id,true);if(h.version()!=version)throw new ConcurrentModificationException();if(h.legalHold()||h.retainUntil()!=null&&h.retainUntil().isAfter(now))throw new SecurityException("Document retention protection");int count;
        if(h.status()==Status.DELETED){try(var s=c.prepareStatement("SELECT count(*) FROM retail_memory.document_versions WHERE tenant_id=? AND document_id=?")){id(s,1,a,id);try(var r=s.executeQuery()){r.next();count=r.getInt(1);}}try(var s=c.prepareStatement("DELETE FROM retail_memory.documents WHERE tenant_id=? AND document_id=?")){id(s,1,a,id);s.executeUpdate();}}
        else try(var s=c.prepareStatement("DELETE FROM retail_memory.document_versions WHERE tenant_id=? AND document_id=? AND version<?")){id(s,1,a,id);s.setLong(3,version);count=s.executeUpdate();}
        audit(c,a,"CLEANUP",id,version,count);return count;
    });}
}
