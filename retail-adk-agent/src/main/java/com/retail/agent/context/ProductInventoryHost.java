package com.retail.agent.context;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retail.agent.context.MemoryTypes.Origin.TOOL_RESULT;
import static com.retail.agent.context.MemoryTypes.Sensitivity.INTERNAL;

/** Local, operator-authenticated host for the existing Spring MCP demonstration scope. */
final class ProductInventoryHost implements AutoCloseable {
    private static final String SERVER = "local-stock";
    private static final String TENANT = "retail-demo";
    private static final String USER = "analyst";
    private static final String SESSION = "product-inventory-cli";
    private static final String TASK = "product-inventory";
    private static final String SOURCE = "product-availability-mcp";

    private final TrustedCallerBoundary.Deployment deployment;
    private final TrustedCallerBoundary.IssuedCredential credential;
    private final TrustedCallerBoundary.Caller caller;
    private final SecureMcpGateway gateway;

    static ProductInventoryHost fromEnvironment(GovernanceAudit audit, Clock clock) {
        var endpoint = URI.create(required("MCP_SERVER_URL"));
        if (!"127.0.0.1".equals(endpoint.getHost()) || !"/mcp".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Use the approved literal loopback Spring MCP endpoint");
        }
        var auditFile = Path.of(required("MCP_AUDIT_FILE"));
        if (!auditFile.isAbsolute()) {
            throw new IllegalArgumentException("An absolute secured MCP audit path is required");
        }
        byte[] key = Base64.getDecoder().decode(required("MCP_DELEGATION_KEY"));
        try {
            return new ProductInventoryHost(new SdkMcpTransport(endpoint), key, audit,
                    new McpFileAuditSink(auditFile), clock, Duration.ofSeconds(5));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    ProductInventoryHost(McpTransport transport, byte[] key, GovernanceAudit audit,
                         SecureMcpGateway.AuditSink gatewayAudit, Clock clock, Duration timeout) {
        var delegation = new McpDelegation(key, clock);
        deployment = TrustedCallerBoundary.bootstrap(clock);
        var identity = new ContextIdentity(TENANT, USER, Set.of(SESSION), Set.of(TASK), Set.of(), INTERNAL);
        var sources = new SourceRegistry(Map.of(SOURCE, new SourceRegistry.Source(SOURCE, TENANT, USER,
                TOOL_RESULT, "Approved product-level Spring inventory MCP")));
        var memory = new InMemoryMemoryRepository(clock, new MemoryWritePolicy(sources, clock), new ContextAuditLog(clock));
        var governance = new GovernedMemoryService(deployment.boundary(), memory, sources, audit);
        var policy = RetailInventoryMcpPolicies.tools().stream()
                .filter(p -> p.alias().equals(ProductInventoryLookup.ALIAS)).findFirst().orElseThrow();
        credential = deployment.provisioner().issue(identity, Set.of(TrustedCallerBoundary.Operation.READ),
                INTERNAL, clock.instant().plusSeconds(600));
        caller = governance.authenticate(credential.token(), SESSION, TASK);
        gateway = new SecureMcpGateway(SERVER, SOURCE, List.of(policy), transport, governance, delegation.issuer(),
                grant -> TENANT.equals(grant.tenant()) && USER.equals(grant.user()) && SESSION.equals(grant.session())
                        && TASK.equals(grant.task()) && policy.alias().equals(grant.alias()), gatewayAudit, clock, timeout);
    }

    ProductKnowledgeResponse answer(ProductKnowledgeAgent agent, String question) {
        return agent.answer(question, gateway, caller);
    }

    ProductKnowledgeResponse answer(ProductKnowledgeAgent agent, String question, java.util.function.Consumer<Runnable> checks) {
        return agent.answer(question, gateway, caller, checks);
    }

    @Override
    public void close() {
        gateway.close();
        deployment.provisioner().revoke(credential.authenticationId());
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required MCP host setting is missing");
        }
        return value;
    }
}
