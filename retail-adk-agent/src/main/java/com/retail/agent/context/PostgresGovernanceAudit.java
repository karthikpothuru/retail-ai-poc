package com.retail.agent.context;

import javax.sql.DataSource;
import java.sql.Connection;

/** Independent append-only security audit connection. Never borrows the memory transaction. */
public final class PostgresGovernanceAudit implements GovernanceAudit {
    private final DataSource writer;
    /** Caller owns/ closes this independent pool. Credentials are never shared with the runtime login. */
    public static com.zaxxer.hikari.HikariDataSource writerPool(java.util.function.Function<String,String> environment){
        return MemoryRepositoryProvider.pool(key->switch(key){
            case "MEMORY_POSTGRES_URL" -> environment.apply("MEMORY_POSTGRES_AUDIT_URL");
            case "MEMORY_POSTGRES_USER" -> environment.apply("MEMORY_POSTGRES_AUDIT_USER");
            case "MEMORY_POSTGRES_PASSWORD" -> environment.apply("MEMORY_POSTGRES_AUDIT_PASSWORD");
            default -> environment.apply(key);
        },false);
    }
    public PostgresGovernanceAudit(DataSource writer){this.writer=java.util.Objects.requireNonNull(writer);PostgresTransactions.requireRole(writer,"retail_memory_audit_writer");}
    public void independent(Event event,ContextIdentity.Access ignored){
        if(event.outcome().equals("SUCCESS"))throw new IllegalArgumentException("Successful mutations require their memory transaction");
        try(var c=writer.getConnection()) {c.setAutoCommit(false);try{insert(c,event);c.commit();}catch(Exception e){c.rollback();
            // Only the random event-ID primary key is unique. A replayed event is already durable.
            if(e instanceof java.sql.SQLException sql&&"23505".equals(sql.getSQLState()))return;
            throw e;}}
        catch(Exception e){throw new IllegalStateException("Independent governance audit unavailable",e);}
    }
    static void insert(Connection c,Event e)throws Exception {
        try(var s=c.prepareStatement("INSERT INTO retail_memory.governance_events(event_id,occurred_at,tenant_id,user_id,authentication_id,operation,outcome,reason,memory_id,memory_version) VALUES (?,?,?,?,?,?,?,?,?,?)")){
            s.setObject(1,e.eventId());s.setTimestamp(2,java.sql.Timestamp.from(e.occurredAt()));s.setString(3,e.tenantId());s.setString(4,e.userId());s.setObject(5,e.authenticationId());s.setString(6,e.operation());s.setString(7,e.outcome());s.setString(8,e.reason());s.setObject(9,e.memoryId());s.setObject(10,e.memoryVersion());s.executeUpdate();
        }
    }
}
