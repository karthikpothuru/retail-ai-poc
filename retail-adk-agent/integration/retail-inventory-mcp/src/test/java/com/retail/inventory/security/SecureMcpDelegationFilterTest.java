package com.retail.inventory.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecureMcpDelegationFilterTest {
    final byte[] key=new byte[32];final String encoded=Base64.getEncoder().encodeToString(key);
    final SecureMcpDelegationFilter filter=new SecureMcpDelegationFilter(encoded,"retail-demo","analyst","local-stock","get_inventory,get_reorder_status,get_sales_summary");
    final String requestId=UUID.randomUUID().toString();
    String token(String tenant,String tool,long expiry)throws Exception{
        var json=JsonMapper.builder().build().writeValueAsBytes(Map.of("aud","local-stock","tenant",tenant,"user","analyst","tool",tool,"request",requestId,"exp",expiry));
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return "Bearer "+body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    MockHttpServletResponse invoke(String token,String body)throws Exception{
        var request=new MockHttpServletRequest("POST","/mcp");request.setContentType("application/json");request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));if(token!=null)request.addHeader("Authorization",token);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{assertEquals(body,new String(req.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));res.getWriter().write("accepted");});return response;
    }
    String call(String args){return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_inventory\",\"arguments\":"+args+",\"_meta\":{\"retail/requestId\":\""+requestId+"\"}}}";}
    @Test void failsClosedWithoutKey(){assertThrows(IllegalStateException.class,()->new SecureMcpDelegationFilter("","retail-demo","analyst","local-stock","get_inventory"));}
    @Test void rejectsAnonymousAndExpired()throws Exception{assertEquals(401,invoke(null,"{}").getStatus());assertEquals(401,invoke(token("retail-demo","discovery",Instant.now().minusSeconds(1).getEpochSecond()),"{}").getStatus());}
    @Test void rejectsCrossTenant()throws Exception{assertEquals(403,invoke(token("other-tenant","get_inventory",Instant.now().plusSeconds(60).getEpochSecond()),call("{\"productId\":\"WM-101\"}")).getStatus());}
    @Test void enforcesToolScopeAndRequestId()throws Exception{assertEquals(403,invoke(token("retail-demo","get_reorder_status",Instant.now().plusSeconds(60).getEpochSecond()),call("{\"productId\":\"WM-101\"}")).getStatus());assertEquals(403,invoke(token("retail-demo","get_inventory",Instant.now().plusSeconds(60).getEpochSecond()),call("{\"productId\":\"WM-101\"}").replace(requestId,UUID.randomUUID().toString())).getStatus());}
    @Test void rejectsIdentityAndUnknownProductArguments()throws Exception{var credential=token("retail-demo","get_inventory",Instant.now().plusSeconds(60).getEpochSecond());assertEquals(403,invoke(credential,call("{\"productId\":\"WM-101\",\"tenantId\":\"other\"}")).getStatus());assertEquals(403,invoke(credential,call("{\"productId\":\"UNKNOWN\"}")).getStatus());}
    @Test void passesValidRequestWithoutConsumingBody()throws Exception{var response=invoke(token("retail-demo","get_inventory",Instant.now().plusSeconds(60).getEpochSecond()),call("{\"productId\":\"WM-101\"}"));assertEquals(200,response.getStatus());assertEquals("accepted",response.getContentAsString());}
}
