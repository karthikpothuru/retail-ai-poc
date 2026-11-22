package com.retail.agent.context;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static com.retail.agent.context.MemoryTypes.*;
import static com.retail.agent.context.EnterpriseDocument.*;

/** Offline synthetic demonstration. Production hosts must supply their authenticated caller and source registry. */
public final class DocumentGroundingCliRunner {
    private DocumentGroundingCliRunner(){}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("retail-document-demo-");
        try{
            Files.writeString(root.resolve("policy.md"),"# Retail replenishment\n\nReview recorded cycle-count evidence before advisory replenishment. Never place an order automatically.");
            var clock=Clock.systemUTC();var sources=new SourceRegistry(Map.of("retail-docs",new SourceRegistry.Source("retail-docs","retail-demo",null,Origin.RETRIEVED_EVIDENCE,"Synthetic retail policy")));
            var boundary=TrustedCallerBoundary.bootstrap(clock);var identity=new ContextIdentity("retail-demo","analyst",Set.of("demo-session"),Set.of("demo-task"),Set.of(),Sensitivity.INTERNAL);
            var token=boundary.provisioner().issue(identity,Set.of(TrustedCallerBoundary.Operation.DOCUMENT_INGEST,TrustedCallerBoundary.Operation.DOCUMENT_READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(600));
            var caller=boundary.boundary().authenticate(token.token(),"demo-session","demo-task");
            var service=new GovernedDocumentService(boundary.boundary(),new InMemoryDocumentRepository(),sources,new DocumentExtractor(root,1000000,100000),new DocumentChunker(256,32),new FakeEmbeddingProvider(768),(e,a)->{},clock);
            var head=service.ingest(caller,new Draft(UUID.randomUUID(),0,"retail-docs","synthetic-local","text/markdown",Sensitivity.INTERNAL,Set.of(),clock.instant(),clock.instant().plusSeconds(3600)),Path.of("policy.md"));
            var result=service.retrieve(caller,new GovernedDocumentService.Query("replenishment recorded evidence",5,0,5000));
            System.out.println("Offline document demonstration; no Gemini, MCP or PostgreSQL calls.");System.out.println("Document version="+head.version()+"; authorized evidence="+result.evidence().size());
            result.evidence().forEach(e->System.out.println(e.citation().marker()+" "+e.citation().location().kind()+"="+e.citation().location().ordinal()+" hash="+e.citation().contentHash()));
            boundary.provisioner().revoke(token.authenticationId());
        }finally{Files.deleteIfExists(root.resolve("policy.md"));Files.deleteIfExists(root);}
    }
}
