package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Requires a separately deployed Spring Boot endpoint implementing the documented delegation contract. */
@EnabledIfSystemProperty(named="runLiveMcpTests",matches="true")
class McpSpringEndpointLiveTest {
    @Test void configuredSpringEndpointRequiresAuthenticationAndExecutesApprovedTool()throws Exception {
        String url=System.getenv("MCP_SERVER_URL"),encoded=System.getenv("MCP_DELEGATION_KEY");
        assertThat(url!=null&&!url.isBlank()).withFailMessage("Configure MCP_SERVER_URL privately").isTrue();
        assertThat(encoded!=null&&!encoded.isBlank()).withFailMessage("Configure dedicated MCP_DELEGATION_KEY privately").isTrue();
        var endpoint=URI.create(url);assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
        var http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
        var denied=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(denied.statusCode()).isIn(401,403);
        var delegation=new McpDelegation(Base64.getDecoder().decode(encoded),Clock.systemUTC());var transport=new SdkMcpTransport(endpoint);
        var discovery=delegation.issuer().issue(new McpDelegation.Claims("local-stock","retail-demo","analyst","discovery",UUID.randomUUID(),Instant.now().plusSeconds(60)));
        assertThat(transport.discover(discovery,Duration.ofSeconds(5))).anyMatch(t->t.name().equals("stock_health"));
        UUID requestId=UUID.randomUUID();var credential=delegation.issuer().issue(new McpDelegation.Claims("local-stock","retail-demo","analyst","stock_health",requestId,Instant.now().plusSeconds(60)));
        var result=transport.call(credential,"stock_health",Map.of("warehouseId","WH-CENTRAL-01"),requestId,Duration.ofSeconds(5));assertThat(result.error()).isFalse();assertThat(result.text()).isNotEmpty();
        var wrong=delegation.issuer().issue(new McpDelegation.Claims("local-stock","forbidden-tenant","analyst","discovery",UUID.randomUUID(),Instant.now().plusSeconds(60)));
        assertThatThrownBy(()->transport.discover(wrong,Duration.ofSeconds(5))).isInstanceOf(RuntimeException.class);
        System.out.println("Live Spring MCP evidence: anonymous/cross-tenant denied; authenticated stock_health completed.");
    }
}
