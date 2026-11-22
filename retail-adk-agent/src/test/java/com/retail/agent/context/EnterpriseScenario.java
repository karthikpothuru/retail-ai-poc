package com.retail.agent.context;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import static com.retail.agent.context.MemoryTypes.*;

/** Synthetic host fixture, reused with in-memory or real PostgreSQL storage and fake or real transports. */
final class EnterpriseScenario implements AutoCloseable {
    final Clock clock=Clock.systemUTC();final TrustedCallerBoundary.Deployment boundary=TrustedCallerBoundary.bootstrap(clock);
    final SourceRegistry sources;final ContextAuditLog audit=new ContextAuditLog(clock);
    final List<GovernanceAudit.Event> denials=new CopyOnWriteArrayList<>();final List<SecureMcpGateway.Audit> toolAudits=new CopyOnWriteArrayList<>();
    final GovernedMemoryService governance;final GovernedDocumentService documents;final MemoryRepository memories;final SemanticMemoryRetrieval semantic;
    final TrustedCallerBoundary.Caller administrator,globalAdministrator,analyst;final ContextIdentity.Access adminAccess;final ContextAwareAdkRuntime runtime;final SecureMcpGateway gateway;
    final UUID documentId=UUID.randomUUID();final List<UUID> memoryIds=new ArrayList<>();
    EnterpriseScenario(Path root,MemoryRepository memory,DocumentRepository document,SemanticMemoryRetrieval retrieval,EmbeddingProvider embeddings,
            McpTransport transport,McpDelegation.Issuer issuer,GovernanceAudit independent,boolean failAudit)throws Exception{
        sources=sources();memories=memory==null?new InMemoryMemoryRepository(clock,new MemoryWritePolicy(sources,clock),audit):memory;semantic=retrieval;
        GovernanceAudit sink=(e,a)->{denials.add(e);if(independent!=null)independent.independent(e,a);};
        governance=new GovernedMemoryService(boundary.boundary(),memories,sources,sink);
        var identity=new ContextIdentity("retail-demo","analyst",Set.of("enterprise-s1","enterprise-s2"),Set.of("reconcile"),EnumSet.allOf(Capability.class),Sensitivity.INTERNAL);
        var admin=boundary.provisioner().issue(identity,EnumSet.allOf(TrustedCallerBoundary.Operation.class),Sensitivity.INTERNAL,clock.instant().plusSeconds(3600));administrator=governance.authenticate(admin.token(),"enterprise-s1","reconcile");globalAdministrator=governance.authenticate(admin.token(),null,null);adminAccess=administrator.access();
        var readOnly=new ContextIdentity(identity.tenantId(),identity.userId(),identity.sessions(),identity.tasks(),Set.of(),Sensitivity.INTERNAL);
        var token=boundary.provisioner().issue(readOnly,Set.of(TrustedCallerBoundary.Operation.READ,TrustedCallerBoundary.Operation.DOCUMENT_READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(3600));analyst=governance.authenticate(token.token(),"enterprise-s1","reconcile");
        documents=new GovernedDocumentService(boundary.boundary(),document==null?new InMemoryDocumentRepository():document,sources,new DocumentExtractor(root,1000000,100000),new DocumentChunker(1024,64),embeddings,sink,clock);
        Files.writeString(root.resolve("reconciliation.md"),"# Inventory reconciliation procedure\n\nFor product inventory reconciliation, obtain current actual and expected counts from the approved product service. Calculate variance as actual minus expected. Review recorded cycle-count and movement evidence before assigning a cause; quantities alone do not establish theft or a specific loss cause. Check reorder status and sample sales context when relevant. Sales sample quantities are not date-filtered. Recommendations are advisory and never create purchase orders.");
        documents.ingest(administrator,new EnterpriseDocument.Draft(documentId,0,"docs","synthetic-retail","text/markdown",Sensitivity.INTERNAL,Set.of(),clock.instant(),null),Path.of("reconciliation.md"));
        add(Type.WORKING,"Active inventory investigation of KB-201 in the separate product service. Do not map this product to a native warehouse SKU.","user",Verification.CANDIDATE,Map.of());
        add(Type.EPISODIC,"Previous inventory reconciliation requested recorded evidence; no root cause was confirmed.","conversation",Verification.CANDIDATE,Map.of());
        add(Type.SEMANTIC,"Inventory variance equals actual inventory minus expected inventory; counts do not establish a root cause.","knowledge",Verification.VERIFIED,Map.of());
        add(Type.PROCEDURAL,"Inventory reconciliation procedure: obtain current product tool counts, inspect recorded evidence and keep reorder recommendations advisory.","procedure",Verification.APPROVED,Map.of("operations",List.of("inventory.assess")));
        gateway=new SecureMcpGateway("local-stock","mcp",RetailInventoryMcpPolicies.tools(),transport,governance,issuer,g->g.tenant().equals("retail-demo")&&g.user().equals("analyst")&&g.task().equals("reconcile"),e->{toolAudits.add(e);if(failAudit)throw new IllegalStateException("Synthetic audit outage");},clock,Duration.ofSeconds(3));
        var catalog=new ApprovedInstructionCatalog(Map.of("assistant",new ApprovedInstructionCatalog.Instruction("assistant","Investigate product KB-201 using named MCP product tools and authorized reconciliation evidence. Never invent a mapping to native warehouse SKUs.","system",Origin.SYSTEM,Sensitivity.INTERNAL),"advisory",new ApprovedInstructionCatalog.Instruction("advisory","Use current tool quantities. Do not infer undocumented causes. Reorder is advisory. Cite supplied DOC markers for document-grounded claims.","policy",Origin.POLICY,Sensitivity.INTERNAL)),sources);
        runtime=new ContextAwareAdkRuntime(governance,sources,catalog,audit,clock,InMemoryRetailInventoryStore.createWithDefaultData(),semantic,List.of("assistant"),List.of("advisory")).withDocumentGrounding(new DocumentGrounding(documents,5,-1,16000)).withMcpGateway(gateway);
    }
    private void add(Type type,String content,String source,Verification verification,Map<String,Object> metadata){var saved=governance.create(type==Type.WORKING?administrator:globalAdministrator,new MemoryRecord.Draft(type,content,metadata,source,null,verification,Sensitivity.INTERNAL));memoryIds.add(saved.id());if(semantic!=null&&(type==Type.SEMANTIC||type==Type.PROCEDURAL))semantic.index(adminAccess,saved.id(),saved.version());}
    ContextAwareAdkRuntime.Request request(int budget){return new ContextAwareAdkRuntime.Request("inventory.assess",new ContextAssemblyService.Request(new ContextAssemblyService.Data("goal","Investigate KB-201 inventory reconciliation. Use mcp_get_inventory for actual and expected counts; obtain mcp_get_reorder_status and mcp_get_sales_summary from 2026-11-01 through 2026-11-18. Explain the variance using returned business data, relevant governed memory and the authorized reconciliation procedure. Distinguish unknown causes; sales quantity is not date-filtered. Cite exact supplied DOC markers. Do not invent warehouse mappings or purchase orders.","user",Sensitivity.INTERNAL),List.of(),List.of(),List.of(),List.of("assistant"),List.of("advisory"),budget,20),List.of());}
    static SourceRegistry sources(){var m=new HashMap<String,SourceRegistry.Source>();for(var e:Map.of("user",Origin.USER_INPUT,"conversation",Origin.CONVERSATION,"knowledge",Origin.KNOWLEDGE,"procedure",Origin.PROCEDURE,"docs",Origin.RETRIEVED_EVIDENCE,"mcp",Origin.TOOL_RESULT,"system",Origin.SYSTEM,"policy",Origin.POLICY).entrySet())m.put(e.getKey(),new SourceRegistry.Source(e.getKey(),"retail-demo",null,e.getValue(),"Synthetic reviewed source "+e.getKey()));return new SourceRegistry(m);}
    public void close(){gateway.close();}
}
