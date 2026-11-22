package com.retail.agent.context;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static com.retail.agent.context.MemoryTypes.*;
import static org.assertj.core.api.Assertions.*;

/** Destructive fixture setup is permitted ONLY on an explicitly marked disposable test database. */
@EnabledIfSystemProperty(named="runPostgresMemoryTests",matches="true")
class PostgresMemoryIntegrationTest extends ContextFixture {
    static HikariDataSource adminPool,appPool,retentionPool;
    private static String runtimeLogin,retentionLogin,runtimePassword;
    PostgresMemoryRepository postgres;

    @BeforeAll static void database() throws Exception {
        if(!"true".equals(System.getenv("MEMORY_POSTGRES_TEST_DISPOSABLE"))) {
            throw new IllegalStateException("Explicit disposable PostgreSQL test database authorization is required");
        }
        adminPool=MemoryRepositoryProvider.pool(System::getenv,true);
        try(var connection=adminPool.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT current_database()")) {
            if(!rows.next()||!rows.getString(1).equals("retail_memory_it")) { throw new IllegalStateException("Tests require database retail_memory_it"); }
        }
        PostgresMigrations.bootstrap(adminPool); PostgresMigrations.migrate(adminPool); PostgresMigrations.migrate(adminPool);
        String suffix=UUID.randomUUID().toString().replace("-", "");
        runtimeLogin="memory_test_app_"+suffix; retentionLogin="memory_test_ret_"+suffix;
        runtimePassword=UUID.randomUUID().toString()+UUID.randomUUID();
        String retentionPassword=UUID.randomUUID().toString()+UUID.randomUUID();
        provision(runtimeLogin,runtimePassword,"retail_memory_app");
        provision(retentionLogin,retentionPassword,"retail_memory_retention");
        appPool=login(runtimeLogin,runtimePassword,3); retentionPool=login(retentionLogin,retentionPassword,1);
    }

    static void provision(String name,String password,String role) throws Exception {
        try(var c=adminPool.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("SELECT set_config('test.login',?,true),set_config('test.password',?,true),set_config('test.role',?,true)")) {
                s.setString(1,name);s.setString(2,password);s.setString(3,role);s.execute();
            }
            try(var s=c.createStatement()) {
                s.execute("DO $$ BEGIN EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD %L IN ROLE %I',current_setting('test.login'),current_setting('test.password'),current_setting('test.role')); END $$");
            }
            c.commit();
        }
    }
    static HikariDataSource login(String user,String password,int size) {
        var config=new HikariConfig(); config.setJdbcUrl(System.getenv("MEMORY_POSTGRES_MIGRATION_URL"));
        config.setUsername(user); config.setPassword(password); config.setMaximumPoolSize(size);config.setMinimumIdle(0);
        config.setPoolName("memory-integration"); return new HikariDataSource(config);
    }

    @BeforeEach void reset() throws Exception {
        try(var c=adminPool.getConnection();var s=c.createStatement()) {
            s.execute("TRUNCATE retail_memory.memory_heads,retail_memory.memory_versions,retail_memory.audit_events CASCADE");
        }
        postgres=new PostgresMemoryRepository(appPool,clock,policy,audit);
    }

    @AfterAll static void close() throws Exception {
        if(appPool!=null)appPool.close(); if(retentionPool!=null)retentionPool.close();
        if(adminPool!=null) {
            try(var c=adminPool.getConnection();var s=c.createStatement()) {
                // Generated identifiers only, never external input. Roles own no database objects.
                if(runtimeLogin!=null)s.execute("DROP ROLE "+runtimeLogin);
                if(retentionLogin!=null)s.execute("DROP ROLE "+retentionLogin);
            } finally { adminPool.close(); }
        }
    }

    @Test void persistsAllCategoriesAcrossRepositoryAndPoolRecreation() {
        var saved=postgres.create(scope,new MemoryRecord.Draft(Type.WORKING,"Inventory working state",Map.of("nested",Map.of("count",3)),
                "user",null,Verification.CANDIDATE,Sensitivity.INTERNAL));
        postgres.create(global,draft(Type.EPISODIC,"Inventory previous result","conversation",Verification.CANDIDATE));
        postgres.create(global,draft(Type.SEMANTIC,"Inventory verified fact","knowledge",Verification.VERIFIED));
        postgres.create(global,draft(Type.PROCEDURAL,"Inventory approved process","procedure",Verification.APPROVED));
        try(var freshPool=login(runtimeLogin,runtimePassword,1)) {
            var fresh=new PostgresMemoryRepository(freshPool,clock,policy,audit);
            assertThat(fresh.read(scope,saved.id())).contains(saved);
            assertThat(fresh.search(scope,new MemoryRepository.Search(Set.of(),"Inventory",20))).hasSize(4);
        }
    }

    @Test void tenantUserSessionTaskAndClearanceIsolationApplyInDatabase() {
        var saved=postgres.create(scope,draft(Type.WORKING,"Scoped inventory secret","user",Verification.CANDIDATE));
        for(var other:List.of(new ContextIdentity.Access(identity("tenant-b","alice",Sensitivity.RESTRICTED,Set.of(Capability.WRITE_MEMORY)),"session-1","task-1"),
                new ContextIdentity.Access(identity("tenant-a","bob",Sensitivity.RESTRICTED,Set.of(Capability.WRITE_MEMORY)),"session-1","task-1"),
                new ContextIdentity.Access(admin,"session-2","task-1"),new ContextIdentity.Access(admin,"session-1","task-2"))) {
            assertThat(postgres.read(other,saved.id())).isEmpty();
            assertThat(postgres.search(other,new MemoryRepository.Search(Set.of(),"",20))).isEmpty();
            assertThatThrownBy(()->postgres.delete(other,saved.id(),1)).isInstanceOf(SecurityException.class);
        }
        var sensitive=postgres.create(global,new MemoryRecord.Draft(Type.EPISODIC,"Restricted",Map.of(),"user",null,Verification.CANDIDATE,Sensitivity.RESTRICTED));
        assertThat(postgres.read(new ContextIdentity.Access(author,null,null),sensitive.id())).isEmpty();
    }

    @Test void directSqlWithoutTenantContextIsDeniedAndRoleCannotDisableRls() throws Exception {
        postgres.create(global,draft(Type.EPISODIC,"Private inventory","user",Verification.CANDIDATE));
        try(var c=appPool.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM retail_memory.memory_versions")) {
            rows.next();assertThat(rows.getInt(1)).isZero();
        }
        deniedSql("SET LOCAL row_security=off; SELECT * FROM retail_memory.memory_versions");
        deniedSql("ALTER TABLE retail_memory.memory_heads DISABLE ROW LEVEL SECURITY");
        deniedSql("SET LOCAL ROLE retail_memory_owner");
        deniedSql("TRUNCATE retail_memory.memory_heads CASCADE");
        deniedSql("DELETE FROM retail_memory.audit_events");
        deniedSql("UPDATE retail_memory.memory_heads SET legal_hold=false");
        assertThatThrownBy(()->new PostgresMemoryRepository(adminPool,clock,policy,audit)).isInstanceOf(SecurityException.class);
        try(var c=adminPool.getConnection();var statement=c.createStatement()) {
            statement.execute("GRANT retail_memory_retention TO "+runtimeLogin);
            try { assertThatThrownBy(()->new PostgresMemoryRepository(appPool,clock,policy,audit)).isInstanceOf(SecurityException.class); }
            finally { statement.execute("REVOKE retail_memory_retention FROM "+runtimeLogin); }
        }
    }

    @Test void directSqlCannotInsertForeignTenantOrReadAnotherTenantWithinBoundScope() throws Exception {
        postgres.create(global,draft(Type.EPISODIC,"Tenant a","user",Verification.CANDIDATE));
        var b=identity("tenant-b","alice",Sensitivity.RESTRICTED,Set.of(Capability.WRITE_MEMORY));
        var sourceB=new SourceRegistry(Map.of("user",new SourceRegistry.Source("user","tenant-b","alice",Origin.USER_INPUT,"Tenant b source")));
        var repoB=new PostgresMemoryRepository(appPool,clock,new MemoryWritePolicy(sourceB,clock),audit);
        repoB.create(new ContextIdentity.Access(b,null,null),draft(Type.EPISODIC,"Tenant b","user",Verification.CANDIDATE));
        long visible=PostgresTransactions.inScope(appPool,global,c->scalar(c,"SELECT count(*) FROM retail_memory.memory_heads"));
        assertThat(visible).isEqualTo(1);
        deniedSql("INSERT INTO retail_memory.memory_heads(tenant_id,user_id,memory_id,memory_type,current_version,verification,sensitivity,lifecycle) VALUES ('tenant-b','alice','00000000-0000-0000-0000-000000000001','EPISODIC',1,'CANDIDATE',1,'ACTIVE')");
    }

    @Test void pooledConnectionScopeIsClearedAfterCommitFailureAndSessionPoison() throws Exception {
        postgres.create(global,draft(Type.EPISODIC,"Inventory a","user",Verification.CANDIDATE));
        try(var one=login(runtimeLogin,runtimePassword,1)) {
            var repo=new PostgresMemoryRepository(one,clock,policy,audit);
            try(var c=one.getConnection();var s=c.createStatement()) {
                s.execute("SELECT set_config('retail.tenant_id','tenant-a',false),set_config('retail.user_id','alice',false),set_config('retail.clearance','3',false)");
            }
            var outsider=new ContextIdentity.Access(identity("tenant-b","alice",Sensitivity.RESTRICTED,Set.of(Capability.WRITE_MEMORY)),null,null);
            assertThat(repo.search(outsider,new MemoryRepository.Search(Set.of(),"",10))).isEmpty();
            assertThatThrownBy(()->repo.update(global,UUID.randomUUID(),1,draft(Type.EPISODIC,"Invalid","user",Verification.CANDIDATE))).isInstanceOf(SecurityException.class);
            try(var c=one.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.memory_heads")) {
                r.next();assertThat(r.getInt(1)).isZero();
            }
        }
    }

    @Test void updateSupersessionAndRevocationPreserveVersionSemantics() {
        var old=postgres.create(global,draft(Type.SEMANTIC,"Inventory old fact","knowledge",Verification.VERIFIED));
        clock.advanceSeconds(1);
        var next=postgres.update(global,old.id(),1,draft(Type.SEMANTIC,"Inventory new fact","knowledge",Verification.VERIFIED));
        assertThat(next.version()).isEqualTo(2);assertThat(next.supersedesVersion()).isEqualTo(1);
        assertThat(postgres.search(global,new MemoryRepository.Search(Set.of(),"old fact",10))).isEmpty();
        assertThatThrownBy(()->postgres.update(global,old.id(),1,draft(Type.SEMANTIC,"Stale","knowledge",Verification.VERIFIED)))
                .isInstanceOf(java.util.ConcurrentModificationException.class);
        var revoked=postgres.revoke(global,old.id(),2);
        assertThat(revoked.version()).isEqualTo(3);assertThat(postgres.read(global,old.id())).isEmpty();
        assertThat(count("SELECT count(*) FROM retail_memory.memory_versions WHERE lifecycle='SUPERSEDED'")).isEqualTo(2);
    }

    @Test void concurrentWritersHaveOneWinnerAndAtomicAudit() throws Exception {
        var saved=postgres.create(global,draft(Type.EPISODIC,"Inventory initial","user",Verification.CANDIDATE));
        var executor=Executors.newFixedThreadPool(6);
        try {
            var winners=new AtomicInteger();var conflicts=new AtomicInteger();List<Callable<Void>> calls=new ArrayList<>();
            for(int i=0;i<6;i++) calls.add(()->{
                try { postgres.update(global,saved.id(),1,draft(Type.EPISODIC,"Inventory changed","user",Verification.CANDIDATE));winners.incrementAndGet(); }
                catch(java.util.ConcurrentModificationException expected) { conflicts.incrementAndGet(); }
                return null;
            });
            for(var result:executor.invokeAll(calls))result.get();
            assertThat(winners.get()).isEqualTo(1);assertThat(conflicts.get()).isEqualTo(5);
            assertThat(count("SELECT count(*) FROM retail_memory.memory_versions")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM retail_memory.audit_events WHERE action='MEMORY_UPDATE'")).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void failingAuditInsertRollsBackHeadHistoryAndAuditTogether() throws Exception {
        var saved=postgres.create(global,draft(Type.EPISODIC,"Inventory original","user",Verification.CANDIDATE));
        try(var c=adminPool.getConnection();var s=c.createStatement()) {
            s.execute("CREATE FUNCTION retail_memory.test_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='MEMORY_UPDATE' THEN RAISE EXCEPTION 'Injected audit failure'; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER test_reject_audit BEFORE INSERT ON retail_memory.audit_events FOR EACH ROW EXECUTE FUNCTION retail_memory.test_reject_audit()");
            try {
                assertThatThrownBy(()->postgres.update(global,saved.id(),1,draft(Type.EPISODIC,"Uncommitted","user",Verification.CANDIDATE))).isInstanceOf(IllegalStateException.class);
                assertThat(postgres.read(global,saved.id()).orElseThrow().content()).isEqualTo("Inventory original");
                assertThat(count("SELECT count(*) FROM retail_memory.memory_versions")).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM retail_memory.audit_events WHERE action='MEMORY_UPDATE'")).isZero();
            } finally { s.execute("DROP TRIGGER test_reject_audit ON retail_memory.audit_events");s.execute("DROP FUNCTION retail_memory.test_reject_audit()"); }
        }
    }

    @Test void expiryCleanupAndLegalHoldHaveExplicitDurableDeletionSemantics() {
        var expiring=new MemoryRecord.Draft(Type.EPISODIC,"Expiring inventory",Map.of(),"user",clock.instant().plusSeconds(1),Verification.CANDIDATE,Sensitivity.INTERNAL);
        var held=postgres.create(global,expiring);var removable=postgres.create(global,expiring);
        var retention=new PostgresRetentionService(retentionPool);
        retention.configure(global,held.id(),1,true,null,"litigation-hold");
        clock.advanceSeconds(2);
        assertThat(postgres.read(global,removable.id())).isEmpty();
        assertThat(postgres.cleanupExpired(global,10)).isEqualTo(1);
        assertThatThrownBy(()->postgres.delete(global,held.id(),1)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM retail_memory.memory_heads")).isEqualTo(1);
        retention.configure(global,held.id(),1,false,Instant.now().plusSeconds(86400),"minimum-retention");
        assertThatThrownBy(()->postgres.delete(global,held.id(),1)).isInstanceOf(IllegalStateException.class);
        retention.configure(global,held.id(),1,false,null,"released");
        postgres.delete(global,held.id(),1);
        assertThat(count("SELECT count(*) FROM retail_memory.memory_versions")).isZero();
        assertThat(count("SELECT count(*) FROM retail_memory.audit_events WHERE action IN ('MEMORY_DELETE','MEMORY_EXPIRED_DELETE')")).isEqualTo(2);
    }

    @Test void runtimeCannotForgeVerificationOrModifyApprovedProcedure() throws Exception {
        var unprivileged=new ContextIdentity.Access(author,null,null);
        assertThatThrownBy(()->postgres.create(unprivileged,draft(Type.SEMANTIC,"Forged","knowledge",Verification.VERIFIED))).isInstanceOf(SecurityException.class);
        var approved=postgres.create(global,draft(Type.PROCEDURAL,"Inventory approved","procedure",Verification.APPROVED));
        assertThatThrownBy(()->postgres.revoke(unprivileged,approved.id(),1)).isInstanceOf(SecurityException.class);
        int affected=PostgresTransactions.inScope(appPool,unprivileged,c->scalarUpdate(c,"UPDATE retail_memory.memory_heads SET lifecycle='REVOKED'"));
        assertThat(affected).isZero();
        deniedSql("UPDATE retail_memory.memory_versions SET lifecycle='ACTIVE'");
    }

    @Test void searchUsesLiteralParametersAndDurableAuditSurvivesRepositoryRecreation() {
        postgres.create(global,draft(Type.EPISODIC,"Inventory ordinary","user",Verification.CANDIDATE));
        assertThat(postgres.search(global,new MemoryRepository.Search(Set.of(),"' OR 1=1 --",10))).isEmpty();
        var fresh=new PostgresMemoryRepository(appPool,clock,policy,audit);
        assertThat(fresh.search(global,new MemoryRepository.Search(Set.of(Type.EPISODIC),"ordinary",10))).hasSize(1);
        assertThat(count("SELECT count(*) FROM retail_memory.audit_events WHERE action='MEMORY_CREATE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retail_memory.audit_events WHERE action='MEMORY_SEARCH'")).isEqualTo(2);
    }

    @Test void concurrentCreatesRemainDistinct() throws Exception {
        var executor=Executors.newFixedThreadPool(4);
        try {
            List<Callable<MemoryRecord>> calls=new ArrayList<>();
            for(int i=0;i<12;i++)calls.add(()->postgres.create(global,draft(Type.EPISODIC,"Inventory concurrent","user",Verification.CANDIDATE)));
            var ids=new java.util.HashSet<UUID>();for(var result:executor.invokeAll(calls))ids.add(result.get().id());
            assertThat(ids).hasSize(12);assertThat(count("SELECT count(*) FROM retail_memory.audit_events WHERE action='MEMORY_CREATE'")).isEqualTo(12);
        } finally { executor.shutdownNow(); }
    }

    private void deniedSql(String sql) throws Exception {
        try(var c=appPool.getConnection()) {
            c.setAutoCommit(false);PostgresTransactions.bind(c,global);
            try(var s=c.createStatement()) { assertThatThrownBy(()->s.execute(sql)).isInstanceOf(java.sql.SQLException.class); }
            finally { c.rollback(); }
        }
    }
    private long count(String sql) { return PostgresTransactions.inScope(appPool,global,c->scalar(c,sql)); }
    private static long scalar(Connection c,String sql)throws Exception {
        try(var s=c.createStatement();var rows=s.executeQuery(sql)) {rows.next();return rows.getLong(1);}
    }
    private static int scalarUpdate(Connection c,String sql)throws Exception {try(var s=c.createStatement()){return s.executeUpdate(sql);}}
}
