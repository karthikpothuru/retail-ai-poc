package com.retail.agent.context;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Local integration contract, not OAuth token passthrough. Production may replace issuance with token exchange. */
public final class McpDelegation {
    public record Claims(String audience,String tenant,String user,String tool,UUID requestId,Instant expiresAt){}
    public record Credential(String token){@Override public String toString(){return "McpCredential[REDACTED]";}}
    public interface Issuer {Credential issue(Claims claims);}
    private final byte[] key;private final Clock clock;
    public McpDelegation(byte[] key,Clock clock){if(key.length<32)throw new IllegalArgumentException("Dedicated delegation key must contain at least 32 bytes");this.key=key.clone();this.clock=clock;}
    public Issuer issuer(){return claims->{
        validate(claims);if(!claims.expiresAt().isAfter(clock.instant())||claims.expiresAt().isAfter(clock.instant().plusSeconds(120)))throw new SecurityException("Delegation expiry invalid");
        var body=encode(StructuredValues.json(Map.of("aud",claims.audience(),"tenant",claims.tenant(),"user",claims.user(),"tool",claims.tool(),"request",claims.requestId().toString(),"exp",claims.expiresAt().getEpochSecond())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Credential(body+"."+encode(sign(body)));
    };}
    /** Call from the server authentication filter AND the tool handler, before accessing tenant business data. */
    public Claims authorize(String bearer,String audience,String tool,UUID requestId,Predicate<Claims> serverPolicy){
        return authenticate(bearer,audience,c->c.tool().equals(tool)&&c.requestId().equals(requestId)&&serverPolicy.test(c));
    }
    public Claims authenticate(String bearer,String audience,Predicate<Claims> serverPolicy){
        try{
            if(bearer==null||bearer.length()>4096)throw new SecurityException();String[] parts=bearer.split("\\.",-1);
            if(parts.length!=2||!java.security.MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new SecurityException();
            var json=PostgresTransactions.JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            var claims=new Claims(json.path("aud").asText(),json.path("tenant").asText(),json.path("user").asText(),json.path("tool").asText(),UUID.fromString(json.path("request").asText()),Instant.ofEpochSecond(json.path("exp").asLong()));
            validate(claims);
            if(!claims.audience().equals(audience)||!claims.expiresAt().isAfter(clock.instant())||claims.expiresAt().isAfter(clock.instant().plusSeconds(120))||!serverPolicy.test(claims))throw new SecurityException();
            return claims;
        }catch(Exception invalid){throw new SecurityException("MCP server authorization denied");}
    }
    private static void validate(Claims c){ContextIdentity.identifier(c.audience());ContextIdentity.identifier(c.tenant());ContextIdentity.identifier(c.user());ContextIdentity.identifier(c.tool());Objects.requireNonNull(c.requestId());Objects.requireNonNull(c.expiresAt());}
    private byte[] sign(String body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}catch(Exception e){throw new IllegalStateException("Delegation signing unavailable");}}
    private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
