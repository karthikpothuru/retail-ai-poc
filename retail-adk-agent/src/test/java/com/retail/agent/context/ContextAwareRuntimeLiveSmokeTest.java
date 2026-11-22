package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.retail.agent.context.MemoryTypes.*;

@EnabledIfSystemProperty(named="runLiveContextSmokeTest",matches="true")
class ContextAwareRuntimeLiveSmokeTest extends ContextFixture {
    @Test void liveGeminiReceivesContextAndExecutesRealLookup(){
        var deployment=TrustedCallerBoundary.bootstrap(clock);
        var governance=new GovernedMemoryService(deployment.boundary(),repository,sources,(e,a)->{});
        var token=deployment.provisioner().issue(admin,Set.of(TrustedCallerBoundary.Operation.READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(600));
        var caller=governance.authenticate(token.token(),"session-1","task-1");
        repository.create(scope,draft(Type.WORKING,"Active inventory investigation SKU-ELEC-1001 WH-CENTRAL-01","user",Verification.CANDIDATE));
        var runtime=new ContextAwareAdkRuntime(governance,sources,catalog,audit,clock,InMemoryRetailInventoryStore.createWithDefaultData(),null,List.of("system"),List.of("advisory"));
        var input=new ContextAssemblyService.Request(data("goal","Use lookup_inventory to report current inventory for SKU-ELEC-1001 at WH-CENTRAL-01. Use tool evidence, not remembered quantities.","user"),List.of(),List.of(),List.of(),List.of("system"),List.of("advisory"),64000,20);
        var result=runtime.execute(caller,new ContextAwareAdkRuntime.Request("inventory.assess",input,List.of()));
        assertThat(result.answer()).isNotBlank();assertThat(result.events().stream().flatMap(e->e.functionResponses().stream()).toList()).anyMatch(r->r.name().orElse("").equals("lookup_inventory"));
        assertThat(result.context().manifest().selections()).anyMatch(s->s.memoryId()!=null);assertThat(result.writes()).noneMatch(w->w.outcome().equals("CREATED_CANDIDATE"));
        System.out.println("Live context validation: lookup executed; manifest="+result.context().manifest().assemblyId()+"; model calls="+result.requestCosts().size());
    }
}
