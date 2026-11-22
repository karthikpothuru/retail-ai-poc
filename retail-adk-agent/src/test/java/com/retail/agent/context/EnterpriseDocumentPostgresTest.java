package com.retail.agent.context;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.retail.agent.context.EnterpriseDocument.*;
import static com.retail.agent.context.MemoryTypes.*;

/** All 32 prior database contracts rerun on V004, plus document-specific PostgreSQL controls. */
@EnabledIfSystemProperty(named="runDocumentPostgresTests",matches="true")
class EnterpriseDocumentPostgresTest extends ContextAwareRuntimePostgresTest {
    static HikariDataSource docRead,docWrite,docRetain;static final List<String> logins=new ArrayList<>();
    @TempDir Path root;PostgresDocumentRepository documentRepository;GovernedDocumentService documents;
    @BeforeAll static void documentSchema()throws Exception{
        PostgresMigrations.bootstrap(adminPool);PostgresMigrations.migrateDocuments(adminPool);PostgresMigrations.migrateDocuments(adminPool);
        var pools=new ArrayList<HikariDataSource>();for(String role:List.of("retail_document_app","retail_document_ingest","retail_document_retention")){String name="doc_it_"+UUID.randomUUID().toString().replace("-","");String password=UUID.randomUUID().toString()+UUID.randomUUID();provision(name,password,role);logins.add(name);pools.add(login(name,password,2));}
        docRead=pools.get(0);docWrite=pools.get(1);docRetain=pools.get(2);
    }
    @BeforeEach void documentFixture()throws Exception{
        try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("TRUNCATE retail_memory.documents,retail_memory.document_versions,retail_memory.document_chunks,retail_memory.document_embeddings,retail_memory.document_acls,retail_memory.document_audit CASCADE");}
        Files.writeString(root.resolve("policy.txt"),"Inventory approval requires recorded evidence. Replenishment is advisory only.");
        documentRepository=new PostgresDocumentRepository(docRead,docWrite,docRetain);documents=service();
    }
    GovernedDocumentService service(){return new GovernedDocumentService(deployment.boundary(),documentRepository,sources,new DocumentExtractor(root,1000000,100000),new DocumentChunker(256,32),new FakeEmbeddingProvider(768),new PostgresGovernanceAudit(auditPool),clock);}
    Draft document(UUID id,long version,Set<String> readers){return new Draft(id,version,"evidence","local-retail","text/plain",Sensitivity.INTERNAL,readers,clock.instant(),null);}
    GovernedDocumentService.Query documentsQuery(){return new GovernedDocumentService.Query("inventory approval evidence",10,-1,12000);}
    TrustedCallerBoundary.Caller reader(String tenant,String user){var i=identity(tenant,user,Sensitivity.INTERNAL,Set.of());var t=deployment.provisioner().issue(i,Set.of(TrustedCallerBoundary.Operation.DOCUMENT_READ),Sensitivity.INTERNAL,clock.instant().plusSeconds(600));return deployment.boundary().authenticate(t.token(),"session-1","task-1");}
    long scalar(String sql)throws Exception{try(var c=adminPool.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    @AfterAll static void closeDocuments()throws Exception{if(docRead!=null)docRead.close();if(docWrite!=null)docWrite.close();if(docRetain!=null)docRetain.close();if(adminPool!=null)try(var c=adminPool.getConnection();var s=c.createStatement()){for(String name:logins)s.execute("DROP ROLE "+name);logins.clear();}}
    @Test void registrationScopeUsesPgvectorAndExcludesPrivateAndObsoleteDocuments() throws Exception {
        var topic = RegistrationKnowledgeAgent.Topic.REGISTRATION;
        var embeddings = new FakeEmbeddingProvider(768);
        var registrationAudit = new PostgresGovernanceAudit(auditPool);
        var publisher = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS,
                documentRepository, embeddings, registrationAudit, RegistrationKnowledgeAgentTest.CLOCK);
        var reader = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS,
                new PostgresDocumentRepository(docRead, null, null), embeddings, registrationAudit,
                RegistrationKnowledgeAgentTest.CLOCK);
        RegistrationKnowledgeAgentTest.activate(documentRepository, "private-customer-tenant", "private-owner",
                topic, 0, Set.of(RegistrationKnowledgeAgent.READER), RegistrationKnowledgeAgentTest.registrationText(),
                Sensitivity.PUBLIC);
        assertThat(RegistrationKnowledgeCliRunner.ask(reader, "How do I register?"))
                .isEqualTo(DocumentGrounding.insufficient());
        var first = publisher.publish(topic, 0);
        var second = publisher.publish(topic, first.version());
        assertThat(RegistrationKnowledgeCliRunner.ask(reader, "How do I register?"))
                .contains("HTTP 201", "[DOC:" + topic.documentId() + ":v2:").doesNotContain(":v1:");
        publisher.revoke(topic, second.version());
        assertThat(RegistrationKnowledgeCliRunner.ask(reader, "How do I register?"))
                .isEqualTo(DocumentGrounding.insufficient());
        assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit WHERE action='SEARCH'")).isEqualTo(3);
    }
    @Test void conversationalRegistrationRetrievesBothTopicsFromPgvector() throws Exception {
        var registration = RegistrationKnowledgeAgent.Topic.REGISTRATION;
        var consent = RegistrationKnowledgeAgent.Topic.CONSENT;
        var publicAudit = new PostgresGovernanceAudit(auditPool);
        var query = ConversationalRegistrationTest.MULTI;
        var model = ConversationalRegistrationTest.model(ConversationalRegistrationTest.passages(
                ConversationalRegistrationTest.passage(registration, ConversationalRegistrationTest.REGISTRATION_QUOTE),
                ConversationalRegistrationTest.passage(consent, ConversationalRegistrationTest.CONSENT_QUOTE)));
        var publisher = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS, documentRepository,
                new FakeEmbeddingProvider(768), publicAudit, RegistrationKnowledgeAgentTest.CLOCK);
        var reader = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS,
                new PostgresDocumentRepository(docRead, null, null), new FakeEmbeddingProvider(768),
                publicAudit, RegistrationKnowledgeAgentTest.CLOCK,
                new RegistrationSynthesis(model, RegistrationSynthesis.DEFAULT_BUDGET));
        RegistrationKnowledgeAgentTest.activate(documentRepository, "private-customer-tenant", "private-owner",
                RegistrationKnowledgeAgent.Topic.PROFILE, 0, Set.of(RegistrationKnowledgeAgent.READER),
                "Private registration consent requires an undisclosed account token.", Sensitivity.PUBLIC);
        publisher.publish(registration, 0);
        publisher.publish(consent, 0);
        var answer = RegistrationKnowledgeCliRunner.ask(reader, query);
        assertThat(answer).contains("[DOC:" + registration.documentId(), "[DOC:" + consent.documentId())
                .doesNotContain("undisclosed account token");
        assertThat(model.requests.get(0).contents().toString()).doesNotContain("undisclosed account token");
        assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit WHERE action='SEARCH'")).isEqualTo(1);
    }
    @Test void productCatalogUsesScopedSqlAndVersionedPgvectorEvidence() throws Exception {
        PostgresMigrations.migrateProductCatalog(adminPool);
        PostgresMigrations.migrateProductCatalog(adminPool);
        var pg = new PostgresProductCatalog(docRead);
        var registration = new ContextIdentity(RegistrationKnowledgeAgent.TENANT, RegistrationKnowledgeAgent.READER,
                Set.of(), Set.of(), Set.of(), Sensitivity.PUBLIC);
        assertThat(pg.active(new ContextIdentity.Access(registration, null, null))).isEmpty();
        var product = new ContextIdentity(ProductKnowledgeAgent.TENANT, ProductKnowledgeAgent.READER,
                Set.of(), Set.of(), Set.of(), Sensitivity.PUBLIC);
        assertThat(pg.active(new ContextIdentity.Access(product, null, null)))
                .containsExactlyInAnyOrderElementsOf(ProductKnowledgeAgentTest.fixtures());
        var embeddings = new FakeEmbeddingProvider(768);
        var durable = new PostgresGovernanceAudit(auditPool);
        var model = ProductKnowledgeAgentTest.model(
                ProductKnowledgeAgentTest.selections("WM-101", ProductKnowledgeAgentTest.MOUSE_POWER,
                        "KB-201", ProductKnowledgeAgentTest.KEYBOARD_LAYOUT),
                ProductKnowledgeAgentTest.selection("MN-301", ProductKnowledgeAgentTest.MONITOR_INPUT));
        var publisher = new ProductKnowledgeAgent(pg, ProductKnowledgeAgentTest.CORPUS, documentRepository,
                embeddings, durable, ProductKnowledgeAgentTest.CLOCK);
        var reader = new ProductKnowledgeAgent(pg, ProductKnowledgeAgentTest.CORPUS,
                new PostgresDocumentRepository(docRead, null, null), embeddings, durable,
                ProductKnowledgeAgentTest.CLOCK, model);
        ProductKnowledgeAgentTest.activatePrivate(documentRepository);
        for (String sku : List.of("WM-101", "KB-201", "MN-301")) {
            publisher.publish(sku, 0);
        }
        assertThat(reader.answer("wm-101").products()).extracting(ProductCatalog.Product::sku).containsExactly("WM-101");
        assertThat(reader.answer("Compare WM-101 and KB-201 for desk use.").answer())
                .contains(ProductKnowledgeAgentTest.marker("WM-101"), ProductKnowledgeAgentTest.marker("KB-201"));
        assertThat(reader.answer("I need a display with HDMI video input.").answer())
                .contains(ProductKnowledgeAgentTest.marker("MN-301"));
        assertThat(model.requests.get(0).contents().toString())
                .doesNotContain("PRIVATE", ProductKnowledgeAgentTest.marker("MN-301"));
        publisher.revoke("WM-101", 1);
        assertThat(reader.answer("Which battery powers WM-101?").outcome())
                .isEqualTo(ProductKnowledgeResponse.Outcome.INSUFFICIENT);
        assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit WHERE action='SEARCH'")).isEqualTo(3);
    }
    @Test void combinesPostgresProductEvidenceWithApprovedMcpObservation() throws Exception {
        PostgresMigrations.migrateProductCatalog(adminPool);
        var catalog = new PostgresProductCatalog(docRead);
        var embeddings = new FakeEmbeddingProvider(768);
        var durable = new PostgresGovernanceAudit(auditPool);
        var clock = ProductKnowledgeAgentTest.CLOCK;
        var publisher = new ProductKnowledgeAgent(catalog, ProductKnowledgeAgentTest.CORPUS, documentRepository,
                embeddings, durable, clock);
        publisher.publish("KB-201", 0);
        var model = ProductKnowledgeAgentTest.model(ProductKnowledgeAgentTest.selection("KB-201",
                ProductKnowledgeAgentTest.KEYBOARD_LAYOUT));
        var reader = new ProductKnowledgeAgent(catalog, ProductKnowledgeAgentTest.CORPUS,
                new PostgresDocumentRepository(docRead, null, null), embeddings, durable, clock, model);
        var transport = new ProductInventoryIntegrationTest.FakeSpring();
        try (var host = new ProductInventoryHost(transport, new byte[32], durable, audit -> {}, clock,
                java.time.Duration.ofSeconds(1))) {
            var result = host.answer(reader, ProductInventoryIntegrationTest.COMBINED);
            assertThat(result.answer()).contains(ProductKnowledgeAgentTest.marker("KB-201"), "reported by MCP: 15");
            assertThat(result.liveInventoryBySku()).containsOnlyKeys("KB-201");
            assertThat(transport.arguments).containsExactly(Map.of("productId", "KB-201"));
        }
    }
    @Test void customerConsentNamespacesUseExistingRealPostgresMemoryRls() {
        var actualClock = java.time.Clock.systemUTC();
        var repository = new PostgresMemoryRepository(appPool, actualClock,
                ConsentAwareCustomerMemory.writePolicy(actualClock), new ContextAuditLog(actualClock));
        var before = ConsentAwareCustomerMemoryTest.synthetic(true);
        var selected = new java.util.concurrent.atomic.AtomicReference<>(before);
        var gate = new ConsentAwareCustomerMemory(cookie -> Optional.of(selected.get()), repository,
                new PostgresGovernanceAudit(auditPool), actualClock);
        var cookie = ConsentAwareCustomerMemoryTest.FIRST;
        var saved = gate.remember(cookie, ConsentAwareCustomerMemory.DetailLevel.CONCISE);
        assertThat(saved.status()).isEqualTo(ConsentAwareCustomerMemory.Status.READY);
        var row = saved.memory().get(0);
        assertThat(gate.read(cookie).memory()).extracting(GovernedMemoryService.View::id).containsExactly(row.id());
        var changed = new DemoCustomerContextClient.Snapshot(before.customerId(), before.selectionId(), true, UUID.randomUUID(),false,
UUID.randomUUID());
        assertThat(repository.read(ConsentAwareCustomerMemory.access(changed, false), row.id())).isEmpty();
        selected.set(changed);
        assertThat(gate.read(cookie).memory()).isEmpty();
        var other = ConsentAwareCustomerMemoryTest.synthetic(true);
        assertThat(repository.read(ConsentAwareCustomerMemory.access(other, false), row.id())).isEmpty();
        selected.set(new DemoCustomerContextClient.Snapshot(before.customerId(), before.selectionId(), false, UUID.randomUUID(),false,
        UUID.randomUUID()));
        assertThat(gate.read(cookie).status()).isEqualTo(ConsentAwareCustomerMemory.Status.CONSENT_DISABLED);
    }
    @Test void personalizedTurnCombinesSeparatelyScopedPostgresEvidence() throws Exception {
        PostgresMigrations.migrateProductCatalog(adminPool);
        var realClock = java.time.Clock.systemUTC();
        var auditSink = new PostgresGovernanceAudit(auditPool);
        var session = ConsentAwareCustomerMemoryTest.synthetic(true);
        var store = new PostgresMemoryRepository(appPool, realClock, ConsentAwareCustomerMemory.writePolicy(realClock),
                new ContextAuditLog(realClock));
        var memory = new ConsentAwareCustomerMemory(cookie -> Optional.of(session), store, auditSink, realClock);
        var cookie = ConsentAwareCustomerMemoryTest.FIRST;
        assertThat(memory.remember(cookie, ConsentAwareCustomerMemory.DetailLevel.DETAILED).status())
                .isEqualTo(ConsentAwareCustomerMemory.Status.READY);
        var embeddings = new FakeEmbeddingProvider(768);
        var catalog = new PostgresProductCatalog(docRead);
        var registration = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS, documentRepository,
                embeddings, auditSink, realClock);
        registration.publish(RegistrationKnowledgeAgent.Topic.REGISTRATION, 0);
        var scripted = ProductKnowledgeAgentTest.model(ProductKnowledgeAgentTest.selection("KB-201",
                ProductKnowledgeAgentTest.KEYBOARD_LAYOUT));
        var products = new ProductKnowledgeAgent(catalog, ProductKnowledgeAgentTest.CORPUS, documentRepository,
                embeddings, auditSink, realClock, scripted);
        products.publish("KB-201", 0);
        var transport = new ProductInventoryIntegrationTest.FakeSpring();
        try (var host = new ProductInventoryHost(transport, new byte[32], auditSink, event -> {}, realClock,
                java.time.Duration.ofSeconds(1))) {
            var turn = new PersonalizedRetailAgent(registration, products, memory, cookie, host);
            var result = turn.answer("How do I register? Tell me about KB-201 and check its availability.");
            assertThat(result.personalized()).isTrue();
            assertThat(result.answer()).contains("HTTP 201", "Catalog details",
                    ProductKnowledgeAgentTest.marker("KB-201"), "reported by MCP: 15");
            assertThat(scripted.requests.get(0).contents().toString()).doesNotContain("Preferred detail level", "HTTP 201");
        }
    }
    @Test void chatHttpBridgeReturnsOnlyPostgresValidatedRegistrationCitations() throws Exception {
        PostgresMigrations.migrateProductCatalog(adminPool);
        var clock = java.time.Clock.systemUTC();
        var embeddings = new FakeEmbeddingProvider(768);
        var durable = new PostgresGovernanceAudit(auditPool);
        var snapshot = ConsentAwareCustomerMemoryTest.synthetic(false);
        var registration = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS, documentRepository,
                embeddings, durable, clock);
        registration.publish(RegistrationKnowledgeAgent.Topic.REGISTRATION, 0);
        var product = new ProductKnowledgeAgent(new PostgresProductCatalog(docRead), ProductKnowledgeAgentTest.CORPUS,
                documentRepository, embeddings, durable, clock, ProductKnowledgeAgentTest.model());
        try (var bridge = new RetailChatHttpServer(0, RetailChatHttpServerTest.TOKEN,
                java.time.Duration.ofSeconds(10), cookie -> Optional.of(snapshot), (cookie, message) ->
                new PersonalizedRetailAgent(registration, product, null, cookie).answer(message))) {
            bridge.start();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "http://127.0.0.1:" + bridge.port() + "/internal/v1/retail/ask"))
                    .header("Authorization", "Bearer " + RetailChatHttpServerTest.TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"message\":\"How do I register?\",\"sessionCookie\":\"valid-opaque-session-cookie\"}"))
                    .build();
            var response = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            var citation = body.path("citations").get(0);
            assertThat(citation.path("channel").asText()).isEqualTo("REGISTRATION");
            assertThat(body.path("answer").asText()).contains("HTTP 201", citation.path("marker").asText());
            assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit WHERE action='REVALIDATE'"))
                    .isGreaterThan(1);
        }
    }
    @Test void documentsPersistWithCitationsAndDurableAuditAcrossRecreation()throws Exception{
        var id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of()),Path.of("policy.txt"));var result=documents.retrieve(caller,documentsQuery());assertThat(result.evidence()).isNotEmpty();var citation=result.evidence().get(0).citation();assertThat(citation.documentId()).isEqualTo(id);assertThat(citation.contentHash()).hasSize(64);
        assertThat(service().retrieve(caller,documentsQuery()).evidence().get(0).citation()).isEqualTo(citation);assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit WHERE action='INGEST'")).isEqualTo(1);
        try(var c=adminPool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM retail_memory.document_audit WHERE event_id=? AND action='SEARCH'")){s.setObject(1,result.operation());try(var r=s.executeQuery()){r.next();assertThat(r.getLong(1)).isEqualTo(1);}}
    }
    @Test void aclAndTenantRestrictionsRunInsidePostgresNotJustJava()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of("bob")),Path.of("policy.txt"));var bob=reader("tenant-a","bob");var evidence=documents.retrieve(bob,documentsQuery()).evidence();assertThat(evidence).isNotEmpty();
        for(String[] who:List.of(new String[]{"tenant-a","charlie"},new String[]{"tenant-b","alice"})){
            assertThat(documents.retrieve(reader(who[0],who[1]),documentsQuery()).evidence()).isEmpty();
            var access=new ContextIdentity.Access(identity(who[0],who[1],Sensitivity.INTERNAL,Set.of()),"session-1","task-1");
            assertThat(PostgresTransactions.<Long>inScope(docRead,access,c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.document_chunks")){r.next();return r.getLong(1);}})).isZero();
        }
        documents.acl(caller,id,1,Set.of());assertThatThrownBy(()->documents.revalidate(bob,evidence)).isInstanceOf(SecurityException.class);assertThat(documents.retrieve(bob,documentsQuery()).evidence()).isEmpty();
    }
    @Test void directRuntimeRoleCannotDisableRlsWriteOrReleaseHolds()throws Exception{
        documents.ingest(caller,document(UUID.randomUUID(),0,Set.of()),Path.of("policy.txt"));
        try(var c=docRead.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.document_chunks")){r.next();assertThat(r.getLong(1)).isZero();}
        for(String sql:List.of("ALTER TABLE retail_memory.documents DISABLE ROW LEVEL SECURITY","SET LOCAL row_security=off; SELECT * FROM retail_memory.document_chunks","SET LOCAL ROLE retail_memory_owner","UPDATE retail_memory.documents SET legal_hold=false","DELETE FROM retail_memory.documents","SELECT * FROM retail_memory.document_audit")){
            try(var c=docRead.getConnection()){c.setAutoCommit(false);try(var s=c.createStatement()){assertThatThrownBy(()->s.execute(sql)).isInstanceOf(java.sql.SQLException.class);}finally{c.rollback();}}
        }
        assertThatThrownBy(()->new PostgresDocumentRepository(adminPool,null,null)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->new PostgresDocumentRepository(docWrite,null,null)).isInstanceOf(SecurityException.class);
        try(var c=docRetain.getConnection();var s=c.createStatement()){assertThatThrownBy(()->s.executeQuery("SELECT * FROM retail_memory.document_chunks")).isInstanceOf(java.sql.SQLException.class);}
        assertThatThrownBy(()->PostgresTransactions.inScope(docRead,scope,c->{try(var s=c.prepareStatement("INSERT INTO retail_memory.document_audit(event_id,tenant_id,user_id,action,affected_count) VALUES (?,?,?,'INGEST',1)")){s.setObject(1,UUID.randomUUID());s.setString(2,"tenant-a");s.setString(3,"alice");s.executeUpdate();}return null;})).isInstanceOf(RuntimeException.class);
    }
    @Test void activationAndSuccessfulAuditRollBackAtomically()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of()),Path.of("policy.txt"));
        try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE retail_memory.document_audit ADD CONSTRAINT reject_doc_ingest CHECK(action<>'INGEST') NOT VALID");}
        try{assertThatThrownBy(()->documents.ingest(caller,document(id,1,Set.of()),Path.of("policy.txt"))).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM retail_memory.document_versions")).isEqualTo(1);assertThat(documents.retrieve(caller,documentsQuery()).evidence()).allMatch(e->e.citation().version()==1);assertThat(scalar("SELECT count(*) FROM retail_memory.governance_events WHERE operation='DOCUMENT_INGEST' AND outcome='ROLLED_BACK'")).isEqualTo(1);}
        finally{try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE retail_memory.document_audit DROP CONSTRAINT reject_doc_ingest");}}
    }
    @Test void vectorInsertFailureNeverPublishesPartialDocument()throws Exception{
        try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE retail_memory.document_embeddings ADD CONSTRAINT reject_doc_vector CHECK(dimension<>768) NOT VALID");}
        try{assertThatThrownBy(()->documents.ingest(caller,document(UUID.randomUUID(),0,Set.of()),Path.of("policy.txt"))).isInstanceOf(RuntimeException.class);assertThat(scalar("SELECT count(*) FROM retail_memory.documents")).isZero();assertThat(scalar("SELECT count(*) FROM retail_memory.document_chunks")).isZero();assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit WHERE action='INGEST'")).isZero();}
        finally{try(var c=adminPool.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE retail_memory.document_embeddings DROP CONSTRAINT reject_doc_vector");}}
    }
    @Test void updatesInvalidateOldEmbeddingsAndRevocationDeletionStayHidden()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of()),Path.of("policy.txt"));var old=documents.retrieve(caller,documentsQuery()).evidence();Files.writeString(root.resolve("policy.txt"),"Inventory replacement policy. Advisory proposals only.");documents.ingest(caller,document(id,1,Set.of()),Path.of("policy.txt"));
        assertThat(scalar("SELECT count(*) FROM retail_memory.document_embeddings WHERE version=1 AND valid")).isZero();assertThatThrownBy(()->documents.revalidate(caller,old)).isInstanceOf(SecurityException.class);assertThat(documents.retrieve(caller,documentsQuery()).evidence()).allMatch(e->e.citation().version()==2);
        documents.revoke(caller,id,2);assertThat(documents.retrieve(caller,documentsQuery()).evidence()).isEmpty();documents.delete(caller,id,2);assertThat(documents.retrieve(caller,documentsQuery()).evidence()).isEmpty();
    }
    @Test void legalHoldProtectsCleanupAndAuditSurvivesPhysicalDeletion()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of()),Path.of("policy.txt"));documents.ingest(caller,document(id,1,Set.of()),Path.of("policy.txt"));documents.retention(caller,id,2,true,null);documents.delete(caller,id,2);
        assertThatThrownBy(()->documents.cleanup(caller,id,2)).isInstanceOf(SecurityException.class);assertThat(scalar("SELECT count(*) FROM retail_memory.document_embeddings")).isEqualTo(2);
        documents.retention(caller,id,2,false,null);documents.cleanup(caller,id,2);assertThat(scalar("SELECT count(*) FROM retail_memory.document_chunks")).isZero();assertThat(scalar("SELECT count(*) FROM retail_memory.document_audit")).isPositive();
    }
    @Test void exactVectorRankingThresholdAndConcurrentReplacementAreDeterministic()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of()),Path.of("policy.txt"));
        var nearest=documents.retrieve(caller,new GovernedDocumentService.Query("Inventory approval requires recorded evidence. Replenishment is advisory only.",5,0.99,12000));assertThat(nearest.evidence()).hasSize(1);assertThat(nearest.evidence().get(0).score()).isCloseTo(1,within(0.00001));
        var workers=Executors.newFixedThreadPool(2);try{Callable<Boolean> update=()->{try{documents.ingest(caller,document(id,1,Set.of()),Path.of("policy.txt"));return true;}catch(ConcurrentModificationException expected){return false;}};var futures=workers.invokeAll(List.of(update,update));assertThat(List.of(futures.get(0).get(),futures.get(1).get())).containsExactlyInAnyOrder(true,false);assertThat(documents.retrieve(caller,documentsQuery()).evidence()).allMatch(e->e.citation().version()==2);}finally{workers.shutdownNow();}
    }
    @Test void databaseWallClockExpiryExcludesDirectReads()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,new Draft(id,0,"evidence","local-retail","text/plain",Sensitivity.INTERNAL,Set.of(),clock.instant(),clock.instant().plusSeconds(1)),Path.of("policy.txt"));Thread.sleep(1200);
        assertThat(documents.retrieve(caller,documentsQuery()).evidence()).isEmpty();assertThat(PostgresTransactions.<Long>inScope(docRead,scope,c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM retail_memory.document_embeddings")){r.next();return r.getLong(1);}})).isZero();
    }
    @Test void uncommittedActivationCannotHideOrPublishPartialVersion()throws Exception{
        UUID id=UUID.randomUUID();documents.ingest(caller,document(id,0,Set.of()),Path.of("policy.txt"));
        try(var c=docWrite.getConnection()){c.setAutoCommit(false);PostgresTransactions.bind(c,scope);
            try(var s=c.prepareStatement("UPDATE retail_memory.documents SET current_version=2 WHERE tenant_id=? AND document_id=?")){s.setString(1,"tenant-a");s.setObject(2,id);s.executeUpdate();}
            assertThat(documents.retrieve(caller,documentsQuery()).evidence()).isNotEmpty().allMatch(e->e.citation().version()==1);c.rollback();
        }
        assertThat(documents.retrieve(caller,documentsQuery()).evidence()).allMatch(e->e.citation().version()==1);
    }
}
