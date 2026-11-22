package com.retail.inventory.security;

import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Matches the ADK host's local delegation profile. No caller identity is accepted from arguments. */
public final class DelegationVerifier {
    public record Claims(String audience,String tenant,String user,String tool,UUID requestId,Instant expiresAt){}
    private final byte[] key;private final Clock clock;private final String audience;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public DelegationVerifier(String encodedKey,String audience,Clock clock){
        try{this.key=Base64.getDecoder().decode(encodedKey);}catch(Exception invalid){throw new IllegalStateException("Dedicated MCP delegation key is required");}
        if(key.length<32)throw new IllegalStateException("Dedicated MCP delegation key must contain at least 32 bytes");
        this.clock=clock;this.audience=audience;
    }
    public Claims verify(String authorization){
        try{
            if(authorization==null||!authorization.startsWith("Bearer ")||authorization.length()>4103)throw new SecurityException();
            String[] parts=authorization.substring(7).split("\\.",-1);if(parts.length!=2)throw new SecurityException();
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            if(!java.security.MessageDigest.isEqual(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)),Base64.getUrlDecoder().decode(parts[1])))throw new SecurityException();
            var j=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            var claims=new Claims(j.path("aud").asText(),j.path("tenant").asText(),j.path("user").asText(),j.path("tool").asText(),UUID.fromString(j.path("request").asText()),Instant.ofEpochSecond(j.path("exp").asLong()));
            if(!claims.audience().equals(audience)||!claims.expiresAt().isAfter(clock.instant())||claims.expiresAt().isAfter(clock.instant().plusSeconds(120)))throw new SecurityException();
            for(String value:List.of(claims.tenant(),claims.user(),claims.tool()))if(!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw new SecurityException();
            return claims;
        }catch(Exception invalid){throw new SecurityException("MCP authentication required");}
    }
}
