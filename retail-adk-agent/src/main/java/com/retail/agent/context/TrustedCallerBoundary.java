package com.retail.agent.context;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static com.retail.agent.context.MemoryTypes.*;

/** Server-side opaque session boundary. Provisioner is a host-only capability, never a prompt/tool input. */
public final class TrustedCallerBoundary {
    public enum Operation { READ, CREATE, UPDATE, VERIFY, APPROVE, REVOKE, DELETE, DOCUMENT_READ, DOCUMENT_INGEST, DOCUMENT_MANAGE, DOCUMENT_RETENTION }
    public record IssuedCredential(String token,UUID authenticationId) {
        @Override public String toString(){return "IssuedCredential[REDACTED]";}
    }
    public interface Provisioner {
        IssuedCredential issue(ContextIdentity identity,Set<Operation> operations,Sensitivity maximumDisclosure,Instant expiresAt);
        void revoke(UUID authenticationId);
    }
    public record Deployment(TrustedCallerBoundary boundary,Provisioner provisioner) {}
    private record Principal(UUID id,ContextIdentity identity,Set<Operation> operations,Sensitivity disclosure,Instant expiresAt) {}
    public static final class Caller {
        private final UUID issuer;
        private final Principal principal;
        private final ContextIdentity.Access access;
        private Caller(UUID issuer,Principal principal,ContextIdentity.Access access){this.issuer=issuer;this.principal=principal;this.access=access;}
        public UUID authenticationId(){return principal.id();}
        ContextIdentity.Access access(){return access;}
        Sensitivity disclosure(){return principal.disclosure();}
        @Override public String toString(){return "AuthenticatedCaller[claims omitted]";}
    }
    private final UUID issuer=UUID.randomUUID();
    private final Clock clock;
    private final Map<String,Principal> tokens=new ConcurrentHashMap<>();
    private final Map<UUID,Principal> active=new ConcurrentHashMap<>();
    private TrustedCallerBoundary(Clock clock){this.clock=java.util.Objects.requireNonNull(clock);}
    public static Deployment bootstrap(Clock clock) {
        var boundary=new TrustedCallerBoundary(clock);
        Provisioner provisioner=new Provisioner(){
            public IssuedCredential issue(ContextIdentity identity,Set<Operation> operations,Sensitivity disclosure,Instant expiry){
                java.util.Objects.requireNonNull(identity);java.util.Objects.requireNonNull(disclosure);
                if(expiry==null||!expiry.isAfter(clock.instant())||expiry.isAfter(clock.instant().plusSeconds(86400))
                        ||disclosure.ordinal()>identity.clearance().ordinal())throw new IllegalArgumentException("Invalid authenticated session bounds");
                String token=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random());
                var principal=new Principal(UUID.randomUUID(),identity,Set.copyOf(operations),disclosure,expiry);
                boundary.tokens.put(hash(token),principal);boundary.active.put(principal.id(),principal);
                return new IssuedCredential(token,principal.id());
            }
            public void revoke(UUID id){boundary.active.remove(id);boundary.tokens.entrySet().removeIf(entry->entry.getValue().id().equals(id));}
        };
        return new Deployment(boundary,provisioner);
    }
    public Caller authenticate(String bearerToken,String sessionId,String taskId) {
        if(bearerToken==null||bearerToken.length()<32||bearerToken.length()>256)throw new SecurityException("Authentication required");
        var principal=tokens.get(hash(bearerToken));
        if(principal==null||!isActive(principal))throw new SecurityException("Authentication required");
        var access=new ContextIdentity.Access(principal.identity(),sessionId,taskId);access.authorize();
        return new Caller(issuer,principal,access);
    }
    ContextIdentity.Access require(Caller caller,Operation operation) {
        if(caller==null||!issuer.equals(caller.issuer)||!isActive(caller.principal))throw new SecurityException("Authentication required");
        caller.access.authorize();
        if(!caller.principal.operations().contains(operation))throw new SecurityException("Operation not authorized");
        return caller.access;
    }
    boolean authentic(Caller caller){return caller!=null&&issuer.equals(caller.issuer)&&isActive(caller.principal);}
    private boolean isActive(Principal principal){return principal.expiresAt().isAfter(clock.instant())&&principal.equals(active.get(principal.id()));}
    private static byte[] random(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return bytes;}
    private static String hash(String token){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
}
