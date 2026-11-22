package com.retail.agent.context;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static com.retail.agent.context.MemoryTypes.*;
import static org.assertj.core.api.Assertions.*;

/** Includes the Phase 6.2 contract suite again on the V002 schema, not a substitute database. */
@EnabledIfSystemProperty(named="runSemanticMemoryTests",matches="true")
class SemanticMemoryIntegrationTest extends PostgresMemoryIntegrationTest {
    private SemanticMemoryRetrieval retrieval;
    @BeforeAll static void vectorSchema()throws Exception {
        try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");}
        PostgresMigrations.migrateSemantic(adminPool);PostgresMigrations.migrateSemantic(adminPool);
    }
    @BeforeEach void retrieval() {
        clock.time.set(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        retrieval=new SemanticMemoryRetrieval(appPool,postgres,new FakeEmbeddingProvider(768),clock,audit);
    }
    private MemoryRecord fact(String text) {return postgres.create(global,draft(Type.SEMANTIC,text,"knowledge",Verification.VERIFIED));}
    private SemanticMemoryRetrieval.Query query(String text,String operation) {return new SemanticMemoryRetrieval.Query(text,operation,Map.of(),20,-1);}

    @Test void exactSimilarityRankingAndDuplicateSuppressionAreStable() {
        var nearest=fact("inventory warehouse stock replenishment");
        var unrelated=fact("employee vacation scheduling");
        var duplicate=fact("INVENTORY WAREHOUSE STOCK REPLENISHMENT");
        for(var memory:List.of(nearest,unrelated,duplicate))retrieval.index(global,memory.id(),1);
        assertThat(retrieval.index(global,nearest.id(),1)).isFalse();
        var results=retrieval.search(global,query("inventory warehouse stock replenishment","inventory.assess"));
        assertThat(results).hasSize(2);
        assertThat(results.get(0).cosine()).isCloseTo(1,within(0.000001));
        assertThat(results.get(1).memory().id()).isEqualTo(unrelated.id());
        assertThat(retrieval.search(global,query("inventory warehouse stock replenishment","inventory.assess")))
                .extracting(result->result.memory().id()).containsExactlyElementsOf(results.stream().map(result->result.memory().id()).toList());
    }

    @Test void metadataAndApprovedOperationApplicabilityAreFilteredInPostgres() {
        var approved=postgres.create(global,new MemoryRecord.Draft(Type.PROCEDURAL,"Review inventory audit evidence",Map.of("operations",List.of("inventory.assess"),"warehouse","WH-CENTRAL-01"),
                "procedure",null,Verification.APPROVED,Sensitivity.INTERNAL));
        retrieval.index(global,approved.id(),1);
        assertThat(retrieval.search(global,query("inventory audit","inventory.assess"))).hasSize(1);
        assertThat(retrieval.search(global,query("inventory audit","purchase.create"))).isEmpty();
        assertThat(retrieval.search(global,new SemanticMemoryRetrieval.Query("inventory audit","inventory.assess",Map.of("warehouse","WH-EAST-02"),20,-1))).isEmpty();
        var unverified=postgres.create(global,draft(Type.SEMANTIC,"Inventory guess","user",Verification.CANDIDATE));
        assertThatThrownBy(()->retrieval.index(global,unverified.id(),1)).isInstanceOf(SecurityException.class);
        var noApplicability=postgres.create(global,draft(Type.PROCEDURAL,"Inventory generic procedure","procedure",Verification.APPROVED));
        assertThatThrownBy(()->retrieval.index(global,noApplicability.id(),1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void updatesInvalidateVectorsAndProceduresUseOnlyTheirCurrentApprovedVersion()throws Exception {
        var old=postgres.create(global,new MemoryRecord.Draft(Type.PROCEDURAL,"Inventory process old",Map.of("operations",List.of("inventory.assess")),
                "procedure",null,Verification.APPROVED,Sensitivity.INTERNAL));
        retrieval.index(global,old.id(),1);
        var next=postgres.update(global,old.id(),1,new MemoryRecord.Draft(Type.PROCEDURAL,"Inventory process revised",Map.of("operations",List.of("inventory.review")),
                "procedure",null,Verification.APPROVED,Sensitivity.INTERNAL));
        assertThat(retrieval.search(global,query("inventory","inventory.assess"))).isEmpty();
        try(var c=adminPool.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT validity FROM retail_memory.memory_embeddings")) {
            assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("INVALID");
        }
        retrieval.index(global,next.id(),2);
        assertThat(retrieval.search(global,query("inventory","inventory.assess"))).isEmpty();
        assertThat(retrieval.search(global,query("inventory","inventory.review"))).singleElement().satisfies(match->{
            assertThat(match.memory().version()).isEqualTo(2);assertThat(match.memory().content()).contains("revised");
        });
    }

    @Test void revocationExpiryAndDeletionMakeEmbeddingsUnavailable()throws Exception {
        var revoked=fact("Inventory revoked fact");retrieval.index(global,revoked.id(),1);postgres.revoke(global,revoked.id(),1);
        assertThat(retrieval.search(global,query("inventory","inventory.assess"))).isEmpty();
        postgres.delete(global,revoked.id(),2);
        var expiring=postgres.create(global,new MemoryRecord.Draft(Type.SEMANTIC,"Inventory expiring fact",Map.of(),"knowledge",
                java.time.Instant.now().plusSeconds(2),Verification.VERIFIED,Sensitivity.INTERNAL));
        retrieval.index(global,expiring.id(),1);
        try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("SELECT pg_sleep(2.1)");}
        clock.advanceSeconds(4);
        assertThat(retrieval.search(global,query("inventory","inventory.assess"))).isEmpty();
        assertThat(retrieval.invalidateExpired(global)).isEqualTo(1);
        postgres.delete(global,expiring.id(),1);
        try(var c=adminPool.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM retail_memory.memory_embeddings")){
            rows.next();assertThat(rows.getInt(1)).isZero();
        }
    }

    @Test void runtimeSqlCannotReadEmbeddingsOutsideBoundTenantOrWithoutContext()throws Exception {
        var saved=fact("Tenant-owned inventory knowledge");retrieval.index(global,saved.id(),1);
        var foreign=new ContextIdentity.Access(identity("tenant-b","alice",Sensitivity.RESTRICTED,Set.of(Capability.WRITE_MEMORY)),null,null);
        assertThat(retrieval.search(foreign,query("inventory","inventory.assess"))).isEmpty();
        long visible=PostgresTransactions.inScope(appPool,foreign,c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.memory_embeddings")){r.next();return r.getLong(1);}});
        assertThat(visible).isZero();
        try(var c=appPool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.memory_embeddings")){r.next();assertThat(r.getLong(1)).isZero();}
        try(var c=appPool.getConnection()) {
            c.setAutoCommit(false);PostgresTransactions.bind(c,global);
            try(var s=c.createStatement()){assertThatThrownBy(()->s.execute("SET LOCAL row_security=off;SELECT * FROM retail_memory.memory_embeddings")).isInstanceOf(java.sql.SQLException.class);}
            finally{c.rollback();}
        }
    }

    @Test void dimensionConstraintsAndModelSpacesCannotBeMixed()throws Exception {
        var fact=fact("Inventory dimension fact");retrieval.index(global,fact.id(),1);
        var other=new SemanticMemoryRetrieval(appPool,postgres,new FakeEmbeddingProvider(128),clock,audit);
        assertThat(other.search(global,query("inventory","inventory.assess"))).isEmpty();
        other.index(global,fact.id(),1);
        assertThat(other.search(global,query("inventory","inventory.assess"))).hasSize(1);
        try(var c=appPool.getConnection()) {
            c.setAutoCommit(false);PostgresTransactions.bind(c,global);
            try(var s=c.prepareStatement("INSERT INTO retail_memory.memory_embeddings(tenant_id,user_id,memory_id,memory_version,sensitivity,model_id,dimension,embedding) VALUES ('tenant-a','alice',?,1,1,'bad-model',3,'[1,0]'::public.vector)")){
                s.setObject(1,fact.id());assertThatThrownBy(s::executeUpdate).isInstanceOf(java.sql.SQLException.class);
            }finally{c.rollback();}
        }
    }

    @Test void contentChangeDuringExternalEmbeddingCannotPublishAStaleVector()throws Exception {
        var saved=fact("Inventory old version");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var fake=new FakeEmbeddingProvider(768);
        EmbeddingProvider blocking=new EmbeddingProvider(){
            public String modelId(){return fake.modelId();}public int dimension(){return 768;}
            public Vector embedQuery(String text){return fake.embedQuery(text);}
            public Vector embedDocument(String text){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return fake.embedDocument(text);}
        };
        var service=new SemanticMemoryRetrieval(appPool,postgres,blocking,clock,audit);var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->service.index(global,saved.id(),1));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            postgres.update(global,saved.id(),1,draft(Type.SEMANTIC,"Inventory new version","knowledge",Verification.VERIFIED));release.countDown();
            assertThatThrownBy(future::get).hasCauseInstanceOf(java.util.ConcurrentModificationException.class);
            assertThat(service.search(global,query("inventory","inventory.assess"))).isEmpty();
        }finally{release.countDown();executor.shutdownNow();}
    }
}
