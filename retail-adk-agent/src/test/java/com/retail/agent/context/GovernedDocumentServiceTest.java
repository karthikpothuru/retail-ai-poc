package com.retail.agent.context;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.retail.agent.context.EnterpriseDocument.*;
import static com.retail.agent.context.MemoryTypes.*;

class GovernedDocumentServiceTest extends DocumentFixture {
    @TempDir Path root;
    @Test void requiresDistinctDocumentGrantsAndAuthenticatedIdentity()throws Exception{
        text(root,"policy.txt","Inventory policy advisory only.");var service=documentService(root);var reader=caller(admin,Set.of(TrustedCallerBoundary.Operation.READ));
        assertThatThrownBy(()->service.ingest(reader,draft(UUID.randomUUID(),0,Set.of()),Path.of("policy.txt"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.retrieve(null,query())).isInstanceOf(SecurityException.class);assertThat(denied).hasSize(2).allMatch(e->e.memoryId()==null);
    }
    @Test void aclAllowsExplicitReadersAndTenantIsolationHidesIdentifiers()throws Exception{
        text(root,"policy.txt","Inventory policy advisory only.");var service=documentService(root);var owner=owner();var id=UUID.randomUUID();service.ingest(owner,draft(id,0,Set.of("bob")),Path.of("policy.txt"));
        assertThat(service.retrieve(bob(),query()).evidence()).isNotEmpty();
        var outsider=caller(identity("tenant-a","charlie",Sensitivity.INTERNAL,Set.of()),Set.of(TrustedCallerBoundary.Operation.DOCUMENT_READ));assertThat(service.retrieve(outsider,query()).evidence()).isEmpty();
        var other=caller(identity("tenant-b","alice",Sensitivity.INTERNAL,Set.of()),Set.of(TrustedCallerBoundary.Operation.DOCUMENT_READ));var result=service.retrieve(other,query());assertThat(result.evidence()).isEmpty();assertThat(result.decisions()).isEmpty();
    }
    @Test void aclVersionChangesRevokePreviouslyReturnedEvidence()throws Exception{
        text(root,"policy.txt","Inventory recorded policy.");var s=documentService(root);var o=owner();var id=UUID.randomUUID();var h=s.ingest(o,draft(id,0,Set.of("bob")),Path.of("policy.txt"));var evidence=s.retrieve(bob(),query()).evidence();
        s.acl(o,id,h.aclVersion(),Set.of());assertThatThrownBy(()->s.revalidate(bob(),evidence)).isInstanceOf(SecurityException.class);assertThat(s.retrieve(bob(),query()).evidence()).isEmpty();
    }
    @Test void versionReplacementRevocationAndDeletionNeverResurrectEvidence()throws Exception{
        text(root,"policy.txt","Inventory old recorded policy.");var s=documentService(root);var o=owner();UUID id=UUID.randomUUID();s.ingest(o,draft(id,0,Set.of()),Path.of("policy.txt"));var old=s.retrieve(o,query()).evidence();text(root,"policy.txt","Inventory new approved reference policy.");
        s.ingest(o,draft(id,1,Set.of()),Path.of("policy.txt"));assertThatThrownBy(()->s.revalidate(o,old)).isInstanceOf(SecurityException.class);assertThat(s.retrieve(o,query()).evidence()).allMatch(e->e.citation().version()==2);
        s.revoke(o,id,2);assertThat(s.retrieve(o,query()).evidence()).isEmpty();assertThatThrownBy(()->s.ingest(o,draft(id,2,Set.of()),Path.of("policy.txt"))).isInstanceOf(SecurityException.class);s.delete(o,id,2);assertThat(s.retrieve(o,query()).evidence()).isEmpty();
    }
    @Test void expiryClassificationBudgetAndRetentionAreEnforced()throws Exception{
        text(root,"policy.txt","Inventory policy ".repeat(20));var s=documentService(root);var o=owner();UUID id=UUID.randomUUID();s.ingest(o,new Draft(id,0,"evidence","local-retail","text/plain",Sensitivity.RESTRICTED,Set.of("bob"),clock.instant(),clock.instant().plusSeconds(10)),Path.of("policy.txt"));
        assertThat(s.retrieve(bob(),query()).evidence()).isEmpty();assertThat(s.retrieve(o,new GovernedDocumentService.Query("inventory",10,-1,10)).evidence()).isEmpty();s.retention(o,id,1,true,null);s.delete(o,id,1);assertThatThrownBy(()->s.cleanup(o,id,1)).isInstanceOf(SecurityException.class);s.retention(o,id,1,false,null);assertThat(s.cleanup(o,id,1)).isPositive();
        UUID expiring=UUID.randomUUID();s.ingest(o,new Draft(expiring,0,"evidence","local-retail","text/plain",Sensitivity.INTERNAL,Set.of(),clock.instant(),clock.instant().plusSeconds(1)),Path.of("policy.txt"));clock.advanceSeconds(2);assertThat(s.retrieve(o,query()).evidence()).isEmpty();
    }
    @Test void embeddingFailureLeavesOldVersionActive()throws Exception{
        text(root,"policy.txt","Inventory retained policy.");var s=documentService(root);var o=owner();UUID id=UUID.randomUUID();s.ingest(o,draft(id,0,Set.of()),Path.of("policy.txt"));
        var bad=new EmbeddingProvider(){public String modelId(){return "bad";}public int dimension(){return 768;}public Vector embedDocument(String t){return new Vector("wrong",1,List.of(1f));}public Vector embedQuery(String t){return embedDocument(t);}};
        assertThatThrownBy(()->service(root,documents,bad).ingest(o,draft(id,1,Set.of()),Path.of("policy.txt"))).isInstanceOf(IllegalArgumentException.class);assertThat(s.retrieve(o,query()).evidence()).allMatch(e->e.citation().version()==1);
    }
    @Test void forgedCitationAndSourceDoNotBecomeAuthorizedEvidence()throws Exception{
        text(root,"policy.txt","Inventory policy advisory.");var s=documentService(root);var o=owner();s.ingest(o,draft(UUID.randomUUID(),0,Set.of()),Path.of("policy.txt"));var real=s.retrieve(o,query()).evidence().get(0);var c=real.citation();
        var forged=new Evidence(new Citation(c.documentId(),c.sourceId(),c.version(),UUID.randomUUID(),c.location(),c.start(),c.end(),c.contentHash()),real.aclVersion(),real.text(),real.classification(),real.modelId(),real.dimensions(),1,real.score());
        assertThatThrownBy(()->s.revalidate(o,List.of(forged))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->s.ingest(o,new Draft(UUID.randomUUID(),0,"system","local","text/plain",Sensitivity.INTERNAL,Set.of(),clock.instant(),null),Path.of("policy.txt"))).isInstanceOf(SecurityException.class);
    }
    @Test void concurrentReplacementsHaveOneWinnerAndNoPartialRetrieval()throws Exception{
        text(root,"policy.txt","Inventory concurrent policy.");var s=documentService(root);var o=owner();UUID id=UUID.randomUUID();s.ingest(o,draft(id,0,Set.of()),Path.of("policy.txt"));var pool=Executors.newFixedThreadPool(3);
        try{Callable<Boolean> update=()->{try{s.ingest(o,draft(id,1,Set.of()),Path.of("policy.txt"));return true;}catch(ConcurrentModificationException expected){return false;}};var futures=pool.invokeAll(List.of(update,update));assertThat(List.of(futures.get(0).get(),futures.get(1).get())).containsExactlyInAnyOrder(true,false);assertThat(s.retrieve(o,query()).evidence()).allMatch(e->e.citation().version()==2);}finally{pool.shutdownNow();}
    }
    @Test void retrievalContinuesOnOldVersionWhileReplacementIsEmbedding()throws Exception{
        text(root,"policy.txt","Inventory stable evidence.");var s=documentService(root);var o=owner();UUID id=UUID.randomUUID();s.ingest(o,draft(id,0,Set.of()),Path.of("policy.txt"));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var fake=new FakeEmbeddingProvider(768);
        var slow=new EmbeddingProvider(){public String modelId(){return fake.modelId();}public int dimension(){return fake.dimension();}public Vector embedDocument(String value){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return fake.embedDocument(value);}public Vector embedQuery(String value){return fake.embedQuery(value);}};
        var worker=Executors.newSingleThreadExecutor();try{var future=worker.submit(()->service(root,documents,slow).ingest(o,draft(id,1,Set.of()),Path.of("policy.txt")));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(s.retrieve(o,query()).evidence()).allMatch(e->e.citation().version()==1);release.countDown();assertThat(future.get(5,TimeUnit.SECONDS).version()).isEqualTo(2);assertThat(s.retrieve(o,query()).evidence()).allMatch(e->e.citation().version()==2);}finally{release.countDown();worker.shutdownNow();}
    }
}
