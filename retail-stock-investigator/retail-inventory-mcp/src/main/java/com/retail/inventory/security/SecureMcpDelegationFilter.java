package com.retail.inventory.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.time.*;
import java.util.*;

/** Single-tenant demonstration boundary: all other tenants/users are rejected, including on discovery. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecureMcpDelegationFilter extends OncePerRequestFilter {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final DelegationVerifier verifier;private final String tenant,user;private final Set<String> tools;
    public SecureMcpDelegationFilter(
            @Value("${app.mcp.delegation-key:${MCP_DELEGATION_KEY:}}") String key,
            @Value("${app.mcp.tenant:retail-demo}") String tenant,
            @Value("${app.mcp.user:analyst}") String user,
            @Value("${app.mcp.audience:local-stock}") String audience,
            @Value("${app.mcp.allowed-tools:get_inventory,get_reorder_status,get_sales_summary}") String allowed){
        this.verifier=new DelegationVerifier(key,audience,Clock.systemUTC());this.tenant=tenant;this.user=user;
        tools=Set.copyOf(Arrays.asList(allowed.split(",")));
        if(!Set.of("get_inventory","get_reorder_status","get_sales_summary").containsAll(tools))throw new IllegalStateException("Unknown MCP tool policy");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !(request.getRequestURI().equals("/mcp")||request.getRequestURI().startsWith("/mcp/"));}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        DelegationVerifier.Claims claims;
        try{claims=verifier.verify(request.getHeader("Authorization"));}catch(SecurityException denied){response.setHeader("WWW-Authenticate","Bearer");response.sendError(401,"MCP authentication required");return;}
        if(!claims.tenant().equals(tenant)||!claims.user().equals(user)||(!claims.tool().equals("discovery")&&!tools.contains(claims.tool()))){response.sendError(403,"MCP scope denied");return;}
        // No public-browser origins are supported by this local service-to-service profile.
        if(request.getHeader("Origin")!=null){response.sendError(403,"MCP origin denied");return;}
        if(request.getMethod().equals("GET")||request.getMethod().equals("DELETE")){chain.doFilter(request,response);return;}
        if(!request.getMethod().equals("POST")){response.sendError(405);return;}
        byte[] body=request.getInputStream().readNBytes(65537);if(body.length>65536){response.sendError(413);return;}
        try{
            var rpc=JSON.readTree(body);if(!rpc.isObject()||!rpc.path("jsonrpc").asText().equals("2.0"))throw new SecurityException();
            String method=rpc.path("method").asText();
            switch(method){
                case "initialize","notifications/initialized","ping","notifications/cancelled" -> {}
                case "tools/list" -> {if(!claims.tool().equals("discovery"))throw new SecurityException();}
                case "tools/call" -> {
                    var params=rpc.path("params");String tool=params.path("name").asText();
                    if(!tools.contains(tool)||!claims.tool().equals(tool)||!claims.requestId().toString().equals(params.path("_meta").path("retail/requestId").asText()))throw new SecurityException();
                    arguments(tool,params.path("arguments"));
                }
                default -> throw new SecurityException();
            }
        }catch(Exception denied){response.sendError(403,"MCP operation or arguments denied");return;}
        chain.doFilter(new BodyRequest(request,body),response);
    }
    private static void arguments(String tool,JsonNode args){
        if(!args.isObject())throw new SecurityException();
        var permitted=tool.equals("get_sales_summary")?Set.of("productId","fromDate","toDate"):Set.of("productId");
        if(args.size()!=permitted.size())throw new SecurityException();
        for(var entry:args.properties())if(!permitted.contains(entry.getKey())||!entry.getValue().isString())throw new SecurityException();
        if(!Set.of("WM-101","KB-201","MN-301").contains(args.path("productId").asText()))throw new SecurityException();
        if(tool.equals("get_sales_summary")){
            String from=args.path("fromDate").asText(),to=args.path("toDate").asText();
            if(from.length()!=10||to.length()!=10||LocalDate.parse(to).isBefore(LocalDate.parse(from)))throw new SecurityException();
        }
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public ServletInputStream getInputStream(){
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous MCP request only");}};
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
    }
}
