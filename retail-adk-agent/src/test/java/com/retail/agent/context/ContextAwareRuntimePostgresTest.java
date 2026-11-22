package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.retail.agent.context.MemoryTypes.*;

/** Real PostgreSQL, real ADK execution, deterministic fake embeddings/model; no Gemini calls. */
@EnabledIfSystemProperty(named="runContextRuntimePostgresTests",matches="true")
class ContextAwareRuntimePostgresTest extends GovernanceMemoryIntegrationTest {
    ContextAwareAdkRuntime runtime(SemanticMemoryRetrieval semantic){return new ContextAwareAdkRuntime(service,sources,catalog,audit,clock,InMemoryRetailInventoryStore.createWithDefaultData(),semantic,List.of("system"),List.of("advisory"));}
    ContextAwareAdkRuntime.Request request(){return new ContextAwareAdkRuntime.Request("inventory.assess",ContextFixture.request(50000),List.of());}
    @Test void postgresRetrievalFlowsThroughGovernanceAndActualAdkRequest(){
        var fact=postgres.create(global,draft(Type.SEMANTIC,"inventory available equals on hand minus reserved","knowledge",Verification.VERIFIED));
        var procedure=postgres.create(global,new MemoryRecord.Draft(Type.PROCEDURAL,"inventory review recorded counts",Map.of("operations",List.of("inventory.assess")),"procedure",null,Verification.APPROVED,Sensitivity.INTERNAL));
        postgres.create(global,draft(Type.SEMANTIC,"inventory unverified guess","user",Verification.CANDIDATE));
        var semantic=new SemanticMemoryRetrieval(appPool,postgres,new FakeEmbeddingProvider(768),clock,audit);semantic.index(global,fact.id(),1);semantic.index(global,procedure.id(),1);
        var model=RuntimeScriptedModel.answer();var result=runtime(semantic).execute(caller,request(),model);
        assertThat(model.requests.get(0).contents().toString()).contains(fact.content(),procedure.content()).doesNotContain("unverified guess");
        assertThat(result.context().manifest().selections()).extracting(AssembledContext.Selection::memoryId).contains(fact.id(),procedure.id());
    }
    @Test void postgresSessionScopeIsPreservedInsideRuntime(){
        var working=postgres.create(scope,draft(Type.WORKING,"inventory private session one","user",Verification.CANDIDATE));
        var token=deployment.provisioner().issue(admin,Set.of(TrustedCallerBoundary.Operation.READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(60));
        var other=service.authenticate(token.token(),"session-2","task-1");var result=runtime(null).execute(other,request(),RuntimeScriptedModel.answer());
        assertThat(result.context().referenceData()).noneMatch(b->working.id().equals(b.memoryId()));
    }
    @Test void reviewedPostRunFactPersistsAsCandidateWithDurableMutationAudit()throws Exception{
        var draft=new MemoryRecord.Draft(Type.SEMANTIC,"inventory submitted observation",Map.of(),"user",clock.instant().plusSeconds(600),Verification.CANDIDATE,Sensitivity.INTERNAL);
        var result=runtime(null).execute(caller,new ContextAwareAdkRuntime.Request("inventory.assess",ContextFixture.request(50000),List.of(draft)),RuntimeScriptedModel.answer());
        var created=result.writes().stream().filter(w->w.outcome().equals("CREATED_CANDIDATE")).findFirst().orElseThrow();
        var saved=postgres.read(scope,created.memoryId()).orElseThrow();assertThat(saved.verification()).isEqualTo(Verification.CANDIDATE);assertThat(saved.scope().tenantId()).isEqualTo("tenant-a");
        try(var c=adminPool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.governance_events WHERE outcome='SUCCESS' AND operation='CREATE'")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
    }
    @Test void databaseRedactionOmitsContentAndMissingIdentityIsDurablyDenied()throws Exception{
        var secret=postgres.create(global,new MemoryRecord.Draft(Type.SEMANTIC,"inventory restricted confidential detail",Map.of(),"knowledge",null,Verification.VERIFIED,Sensitivity.RESTRICTED));
        var token=deployment.provisioner().issue(admin,Set.of(TrustedCallerBoundary.Operation.READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(60));
        var delivery=service.authenticate(token.token(),"session-1","task-1");var model=RuntimeScriptedModel.answer();
        var result=runtime(null).execute(delivery,request(),model);assertThat(model.requests.get(0).contents().toString()).doesNotContain(secret.content());
        assertThat(result.context().manifest().omissions()).contains(new AssembledContext.Omission("memory:"+secret.id(),"CLASSIFICATION_REDACTED"));
        assertThatThrownBy(()->runtime(null).execute(null,request(),RuntimeScriptedModel.answer())).isInstanceOf(SecurityException.class);
        try(var c=adminPool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.governance_events WHERE outcome='DENIED' AND operation='ADK_RUNTIME'")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
    }
}
