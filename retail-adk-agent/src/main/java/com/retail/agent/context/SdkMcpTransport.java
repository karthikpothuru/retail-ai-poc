package com.retail.agent.context;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** One SDK client/credential scope per operation; never shares an authenticated MCP session between users. */
public final class SdkMcpTransport implements McpTransport {
    private final URI endpoint;
    public SdkMcpTransport(URI endpoint){
        if(endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||endpoint.getHost()==null
                ||!(endpoint.getScheme().equals("https")||(endpoint.getScheme().equals("http")&&endpoint.getHost().equals("127.0.0.1"))))throw new IllegalArgumentException("Approved HTTPS or literal loopback HTTP endpoint required");
        this.endpoint=endpoint;
    }
    private McpSyncClient open(McpDelegation.Credential credential,Duration timeout){
        var transport=HttpClientStreamableHttpTransport.builder(endpoint.getScheme()+"://"+endpoint.getRawAuthority())
                .endpoint(endpoint.getRawPath()).connectTimeout(timeout).maxResponseSize(65536).openConnectionOnStartup(false)
                .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                .addHttpRequestCustomizer((builder,method,uri,body,context)->{
                    if(!uri.getScheme().equals(endpoint.getScheme())||!Objects.equals(uri.getRawAuthority(),endpoint.getRawAuthority())||!Objects.equals(uri.getRawPath(),endpoint.getRawPath()))throw new SecurityException("MCP destination changed");
                    builder.timeout(timeout).header("Authorization","Bearer "+credential.token());
                }).build();
        return McpClient.sync(transport).requestTimeout(timeout).initializationTimeout(timeout).loggingConsumer(message->{}).build();
    }
    public List<DiscoveredTool> discover(McpDelegation.Credential credential,Duration timeout){
        try(var client=open(credential,timeout)){
            client.initialize();var result=client.listTools();
            if(result.nextCursor()!=null||result.tools().size()>128)throw new SecurityException("MCP discovery limit exceeded");
            return result.tools().stream().map(t->new DiscoveredTool(t.name(),t.inputSchema())).toList();
        }catch(SecurityException e){throw e;}catch(Exception failure){throw classify(failure);}
    }
    public Reply call(McpDelegation.Credential credential,String tool,Map<String,Object> arguments,UUID requestId,Duration timeout){
        try(var client=open(credential,timeout)){
            client.initialize();var result=client.callTool(new McpSchema.CallToolRequest(tool,arguments,Map.of("retail/requestId",requestId.toString())));
            var text=new ArrayList<String>();
            for(var part:result.content())if(part instanceof McpSchema.TextContent t)text.add(t.text());else throw new InvalidResponse();
            return new Reply(Boolean.TRUE.equals(result.isError()),text);
        }catch(SecurityException|InvalidResponse e){throw e;}catch(Exception failure){throw classify(failure);}
    }
    private static RuntimeException classify(Exception failure){
        Throwable root=failure;
        for(int depth=0;depth<12&&root!=null;depth++,root=root.getCause()){
            if(root instanceof io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException)return new AuthorizationFailure();
            if(root instanceof java.util.concurrent.TimeoutException||root instanceof java.net.http.HttpTimeoutException)return new TimeoutFailure();
        }
        return new ConnectionFailure(failure.getClass());
    }
}
