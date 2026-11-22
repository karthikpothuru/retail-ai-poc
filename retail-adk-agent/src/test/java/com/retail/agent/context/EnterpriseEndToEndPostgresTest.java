package com.retail.agent.context;

import com.google.genai.Client;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Existing 42 database controls plus the combined real PostgreSQL/real Spring scenario. */
@EnabledIfSystemProperty(named="runEnterpriseE2eTests",matches="true")
class EnterpriseEndToEndPostgresTest extends EnterpriseDocumentPostgresTest {
    EnterpriseScenario host(EmbeddingProvider provider)throws Exception{
        var sourceRegistry=EnterpriseScenario.sources();var realClock=Clock.systemUTC();var log=new ContextAuditLog(realClock);
        var memory=new PostgresMemoryRepository(appPool,realClock,new MemoryWritePolicy(sourceRegistry,realClock),log);
        var semantic=new SemanticMemoryRetrieval(appPool,memory,provider,realClock,log);
        var endpoint=URI.create(Objects.requireNonNull(System.getenv("MCP_SERVER_URL")));assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
        var delegation=new McpDelegation(Base64.getDecoder().decode(Objects.requireNonNull(System.getenv("MCP_DELEGATION_KEY"))),realClock);
        return new EnterpriseScenario(root,memory,documentRepository,semantic,provider,new SdkMcpTransport(endpoint),delegation.issuer(),new PostgresGovernanceAudit(auditPool),false);
    }
    void verifyAndExport(EnterpriseScenario host,ContextAwareAdkRuntime.Result result,String model,String embeddings)throws Exception{
        assertThat(result.mcpEvidence()).extracting(SecureMcpGateway.Evidence::tool).contains("mcp_get_inventory","mcp_get_reorder_status","mcp_get_sales_summary");
        assertThat(result.mcpEvidence()).allMatch(e->e.outcome().equals("SUCCESS")||e.duplicate());
        assertThat(result.documents().selections()).anyMatch(s->s.outcome().equals("INCLUDED"));assertThat(result.answer()).contains("15","80","65","[DOC:");
        assertThat(result.context().manifest().selections().stream().filter(s->s.memoryId()!=null).map(AssembledContext.Selection::memoryId)).containsAll(host.memoryIds);
        var report=new LinkedHashMap<>(EnterpriseExecutionEvidence.completed(host.governance,host.analyst,result,host.denials));
        report.put("dependencies",Map.of("postgres","REAL_POSTGRESQL_18_PGVECTOR","mcp","REAL_SPRING_BOOT","model",model,"embeddings",embeddings));
        assertThat(report).containsEntry("status","COMPLETED");
        try(var c=adminPool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM retail_memory.document_audit WHERE event_id=? AND action='SEARCH'")){s.setObject(1,result.documents().retrievalOperation());try(var r=s.executeQuery()){r.next();assertThat(r.getInt(1)).isEqualTo(1);}}
        assertThat(scalar("SELECT count(*) FROM retail_memory.memory_embeddings WHERE validity='VALID'")).isEqualTo(2);
        long mutations=scalar("SELECT count(*) FROM retail_memory.governance_events WHERE outcome='SUCCESS'");assertThat(mutations).isGreaterThanOrEqualTo(4);
        report.put("auditAssertions",Map.of("documentSearchEventCommitted",true,"validMemoryEmbeddingRows",2,"governedSuccessEvents",mutations));
        Path evidence=Path.of("enterprise-evidence",result.executionId()+".json").toAbsolutePath();EnterpriseExecutionEvidence.write(evidence,report);
        System.out.println("ENTERPRISE E2E: status=COMPLETED; execution="+result.executionId()+"; model="+model+"; embeddings="+embeddings+"; MCP tools="+result.mcpEvidence().size()+"; model calls="+result.requestCosts().size()+"; evidence="+evidence.getFileName());
    }
    @Test void deterministicModelUsesRealPostgresMemoryDocumentsAndSpring()throws Exception{
        try(var scenario=host(new FakeEmbeddingProvider(768))){var model=new EnterpriseScriptedModel();var result=scenario.runtime.execute(scenario.analyst,scenario.request(64000),model);verifyAndExport(scenario,result,"SCRIPTED","DETERMINISTIC_FAKE");}
    }
    @Test @EnabledIfSystemProperty(named="runEnterpriseGeminiTests",matches="true")
    void liveGeminiUsesTheCompletePersistentEnterprisePipeline()throws Exception{
        boolean real=Boolean.getBoolean("runEnterpriseRealEmbeddings");
        try(var client=real?Client.builder().build():null;var scenario=host(real?new GeminiEmbeddingProvider(client,768):new FakeEmbeddingProvider(768))){
            var result=scenario.runtime.execute(scenario.analyst,scenario.request(64000));verifyAndExport(scenario,result,"LIVE_GEMINI",real?"REAL_GEMINI_EMBEDDING_001":"DETERMINISTIC_FAKE");
            if(real)assertThat(result.documents().selections()).allMatch(s->s.modelId().equals(GeminiEmbeddingProvider.MODEL)&&s.dimensions()==768);
        }
    }
    @Test void documentRevocationBlocksTheNextRealMcpDispatch()throws Exception{
        try(var scenario=host(new FakeEmbeddingProvider(768))){var model=new EnterpriseScriptedModel();model.onCall=()->{if(model.requests.size()==1)scenario.documents.revoke(scenario.administrator,scenario.documentId,1);};
            assertThatThrownBy(()->scenario.runtime.execute(scenario.analyst,scenario.request(64000),model)).isInstanceOf(RuntimeException.class);assertThat(model.requests).hasSize(1);assertThat(scenario.toolAudits).isEmpty();
            assertThat(scalar("SELECT count(*) FROM retail_memory.governance_events WHERE outcome='DENIED'")).isPositive();
        }
    }
    @Test void realSpringBoundaryRejectsForgedAndCrossTenantDelegation()throws Exception{
        URI endpoint=URI.create(Objects.requireNonNull(System.getenv("MCP_SERVER_URL")));var clock=Clock.systemUTC();var signer=new McpDelegation(Base64.getDecoder().decode(Objects.requireNonNull(System.getenv("MCP_DELEGATION_KEY"))),clock);
        String rpc="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
        var cross=signer.issuer().issue(new McpDelegation.Claims("local-stock","other-tenant","analyst","discovery",UUID.randomUUID(),clock.instant().plusSeconds(60)));
        var otherUser=signer.issuer().issue(new McpDelegation.Claims("local-stock","retail-demo","other-user","discovery",UUID.randomUUID(),clock.instant().plusSeconds(60)));
        var codes=new LinkedHashMap<String,Integer>();for(var entry:Map.of("forged","invalid.signature","crossTenant",cross.token(),"crossUser",otherUser.token()).entrySet()){
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+entry.getValue()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(rpc)).build();int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();codes.put(entry.getKey(),status);
        }assertThat(codes).containsEntry("forged",401).containsEntry("crossTenant",403).containsEntry("crossUser",403);System.out.println("ENTERPRISE SECURITY: real Spring forged=401; cross-tenant=403; cross-user=403; no tool execution.");
    }
}
