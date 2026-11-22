package com.retail.agent.context;

import java.nio.file.*;
import java.util.*;
import static com.retail.agent.context.MemoryTypes.*;
import static com.retail.agent.context.EnterpriseDocument.*;

class DocumentFixture extends ContextFixture {
    final TrustedCallerBoundary.Deployment deployment=TrustedCallerBoundary.bootstrap(clock);
    final List<GovernanceAudit.Event> denied=new java.util.concurrent.CopyOnWriteArrayList<>();
    final GovernanceAudit documentAudit=(e,a)->denied.add(e);
    final DocumentRepository documents=new InMemoryDocumentRepository();
    GovernedDocumentService documentService(Path root){return service(root,documents,new FakeEmbeddingProvider(768));}
    GovernedDocumentService service(Path root,DocumentRepository repository,EmbeddingProvider provider){return new GovernedDocumentService(deployment.boundary(),repository,sources,new DocumentExtractor(root,1000000,100000),new DocumentChunker(256,32),provider,documentAudit,clock);}
    TrustedCallerBoundary.Caller caller(ContextIdentity identity,Set<TrustedCallerBoundary.Operation> operations){var token=deployment.provisioner().issue(identity,operations,identity.clearance(),clock.instant().plusSeconds(3600));return deployment.boundary().authenticate(token.token(),"session-1","task-1");}
    TrustedCallerBoundary.Caller owner(){return caller(admin,EnumSet.allOf(TrustedCallerBoundary.Operation.class));}
    TrustedCallerBoundary.Caller bob(){return caller(identity("tenant-a","bob",Sensitivity.INTERNAL,Set.of()),Set.of(TrustedCallerBoundary.Operation.DOCUMENT_READ));}
    Draft draft(UUID id,long version,Set<String> readers){return new Draft(id,version,"evidence","local-retail","text/plain",Sensitivity.INTERNAL,readers,clock.instant(),null);}
    GovernedDocumentService.Query query(){return new GovernedDocumentService.Query("inventory retail policy",10,-1,12000);}
    static void text(Path root,String filename,String content)throws Exception{Files.writeString(root.resolve(filename),content);}
}
