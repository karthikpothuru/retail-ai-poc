package com.retail.agent.context;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.google.genai.types.Part;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static com.retail.agent.context.MemoryTypes.*;
import static org.assertj.core.api.Assertions.*;

/** Actual supplied Spring server, three reviewed tools, real ADK; model transport is scripted unless gated. */
@EnabledIfSystemProperty(named="runLiveRetailMcpTests",matches="true")
class RetailInventorySpringLiveTest {
    final Clock clock=Clock.systemUTC();
    final List<SecureMcpGateway.Audit> audits=new ArrayList<>();
    SecureMcpGateway gateway;ContextAwareAdkRuntime runtime;TrustedCallerBoundary.Caller caller;McpDelegation delegation;URI endpoint;
    @BeforeEach void host(){
        endpoint=URI.create(Objects.requireNonNull(System.getenv("MCP_SERVER_URL")));assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
        delegation=new McpDelegation(Base64.getDecoder().decode(Objects.requireNonNull(System.getenv("MCP_DELEGATION_KEY"))),clock);
        var sources=new SourceRegistry(Map.of(
            "user",new SourceRegistry.Source("user","retail-demo","analyst",Origin.USER_INPUT,"Host request"),
            "system",new SourceRegistry.Source("system","retail-demo",null,Origin.SYSTEM,"Host catalog"),
            "policy",new SourceRegistry.Source("policy","retail-demo",null,Origin.POLICY,"Host advisory policy"),
            "mcp",new SourceRegistry.Source("mcp","retail-demo",null,Origin.TOOL_RESULT,"Reviewed retail inventory Spring service")));
        var catalog=new ApprovedInstructionCatalog(Map.of(
            "assistant",new ApprovedInstructionCatalog.Instruction("assistant","Use named MCP tools for the separate product demo. Never map product IDs to native warehouse SKUs.","system",Origin.SYSTEM,Sensitivity.INTERNAL),
            "advisory",new ApprovedInstructionCatalog.Instruction("advisory","Never create orders or invent data. Sales quantities are sample totals, not date-filtered.","policy",Origin.POLICY,Sensitivity.INTERNAL)),sources);
        var audit=new ContextAuditLog(clock);var repository=new InMemoryMemoryRepository(clock,new MemoryWritePolicy(sources,clock),audit);
        var deployment=TrustedCallerBoundary.bootstrap(clock);var governed=new GovernedMemoryService(deployment.boundary(),repository,sources,(e,a)->{});
        var identity=new ContextIdentity("retail-demo","analyst",Set.of("spring-live"),Set.of("product-assess"),Set.of(),Sensitivity.INTERNAL);
        var token=deployment.provisioner().issue(identity,Set.of(TrustedCallerBoundary.Operation.READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(600));caller=governed.authenticate(token.token(),"spring-live","product-assess");
        gateway=new SecureMcpGateway("local-stock","mcp",RetailInventoryMcpPolicies.tools(),new SdkMcpTransport(endpoint),governed,delegation.issuer(),g->g.tenant().equals("retail-demo")&&g.user().equals("analyst")&&g.task().equals("product-assess"),audits::add,clock,Duration.ofSeconds(5));
        runtime=new ContextAwareAdkRuntime(governed,sources,catalog,audit,clock,InMemoryRetailInventoryStore.createWithDefaultData(),null,List.of("assistant"),List.of("advisory")).withMcpGateway(gateway);
    }
    @AfterEach void close(){if(gateway!=null)gateway.close();}
    ContextAwareAdkRuntime.Request request(){return new ContextAwareAdkRuntime.Request("inventory.assess",new ContextAssemblyService.Request(new ContextAssemblyService.Data("goal","Use mcp_get_inventory and mcp_get_reorder_status for KB-201. Then use mcp_get_sales_summary for KB-201 from 2026-11-01 through 2026-11-18. Report advisory facts and note sales quantity is not date-filtered.","user",Sensitivity.INTERNAL),List.of(),List.of(),List.of(),List.of("assistant"),List.of("advisory"),64000,20),List.of());}
    int post(String credential,String body)throws Exception{
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("Accept","application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));if(credential!=null)builder.header("Authorization","Bearer "+credential);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test void serverRejectsAnonymousForgedAndCrossTenantClaims()throws Exception{
        String initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        assertThat(post(null,initialize)).isEqualTo(401);assertThat(post("forged.invalid",initialize)).isEqualTo(401);
        var wrong=delegation.issuer().issue(new McpDelegation.Claims("local-stock","other-tenant","analyst","discovery",UUID.randomUUID(),clock.instant().plusSeconds(60)));
        assertThat(post(wrong.token(),initialize)).isEqualTo(403);
    }
    @Test void realAdkExecutesAllThreeActualSpringToolsAndRecordsProvenance(){
        var model=new RuntimeScriptedModel(Part.fromFunctionCall("mcp_get_inventory",Map.of("productId","KB-201")),Part.fromFunctionCall("mcp_get_reorder_status",Map.of("productId","KB-201")),Part.fromFunctionCall("mcp_get_sales_summary",Map.of("productId","KB-201","fromDate","2026-11-01","toDate","2026-11-18")),Part.fromText("Advisory only; recorded tool evidence received."));
        var result=runtime.execute(caller,request(),model);
        assertThat(result.mcpEvidence()).hasSize(3).allMatch(e->e.outcome().equals("SUCCESS")&&e.sourceId().equals("mcp"));assertThat(audits).hasSize(3);
        var responses=result.events().stream().flatMap(e->e.functionResponses().stream()).toList();assertThat(responses).hasSize(3);
        var inventory=responses.stream().filter(r->r.name().orElse("").equals("mcp_get_inventory")).findFirst().orElseThrow();
        String evidence=(String)inventory.response().orElseThrow().get("evidence");assertThat(evidence).contains("KB-201","actualInventory","15","expectedInventory","80");
        assertThat(model.requests.get(3).contents().toString()).contains("reorderRequired","true","not calculated from date-filtered");
        assertThat(result.writes()).noneMatch(w->w.outcome().equals("CREATED_CANDIDATE"));
        System.out.println("SPRING LIVE MCP: all 3 actual tools succeeded; KB-201 actual=15, expected=80; provenance entries=3; unauthorized/cross-tenant controls tested separately.");
    }
    @Test void duplicateGatewayCallIsNotResentToSpring(){
        try(var session=gateway.bind(caller,UUID.randomUUID())){session.tools();var first=session.execute("mcp_get_reorder_status",Map.of("productId","KB-201"));var second=session.execute("mcp_get_reorder_status",Map.of("productId","KB-201"));assertThat(first).isEqualTo(second);assertThat(audits).extracting(SecureMcpGateway.Audit::outcome).containsExactly("SUCCESS","DUPLICATE_SUPPRESSED");}
    }
    @Test @EnabledIfSystemProperty(named="runLiveMcpGeminiTests",matches="true")
    void realGeminiUsesActualSpringMcpTools(){
        var result=runtime.execute(caller,request());assertThat(result.answer()).isNotBlank();
        assertThat(result.mcpEvidence()).extracting(SecureMcpGateway.Evidence::tool).contains("mcp_get_inventory","mcp_get_reorder_status","mcp_get_sales_summary");
        assertThat(result.mcpEvidence()).allMatch(e->e.outcome().equals("SUCCESS")||e.duplicate());
        System.out.println("GEMINI + SPRING MCP LIVE: actual tools="+result.mcpEvidence().stream().map(SecureMcpGateway.Evidence::tool).distinct().toList()+"; model calls="+result.requestCosts().size()+"; execution="+result.executionId());
    }
}
