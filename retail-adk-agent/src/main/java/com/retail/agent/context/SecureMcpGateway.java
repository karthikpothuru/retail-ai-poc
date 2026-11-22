package com.retail.agent.context;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static com.retail.agent.context.MemoryTypes.*;

/** Host-pinned tools only. Caller scope never comes from MCP descriptions, arguments or responses. */
public final class SecureMcpGateway implements AutoCloseable {
    public record ToolPolicy(String alias,String remoteName,String description,McpInputSchema schema,Sensitivity classification,boolean safeIdempotent,Map<String,Object> discoverySchema){
        public ToolPolicy{if(!alias.matches("mcp_[a-z_]{1,36}")||!remoteName.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid pinned MCP name");StructuredValues.content(description);Objects.requireNonNull(schema);Objects.requireNonNull(classification);discoverySchema=StructuredValues.metadata(discoverySchema);}
        public ToolPolicy(String alias,String remoteName,String description,McpInputSchema schema,Sensitivity classification,boolean safeIdempotent){this(alias,remoteName,description,schema,classification,safeIdempotent,schema.json());}
    }
    public record Grant(String tenant,String user,String session,String task,String alias){}
    public record Audit(UUID executionId,UUID requestId,String server,String tool,String tenant,String user,String outcome,int attempts,long durationMillis){}
    public interface AuditSink {void append(Audit event);}
    public record Evidence(UUID requestId,String server,String tool,String sourceId,String outcome,boolean duplicate,long durationMillis){}
    private final String serverId,sourceId;private final List<ToolPolicy> policies;private final McpTransport transport;
    private final GovernedMemoryService governance;private final McpDelegation.Issuer issuer;private final Predicate<Grant> authorize;
    private final AuditSink audit;private final Clock clock;private final Duration timeout;
    private final ThreadPoolExecutor workers;
    public SecureMcpGateway(String serverId,String sourceId,List<ToolPolicy> policies,McpTransport transport,GovernedMemoryService governance,
            McpDelegation.Issuer issuer,Predicate<Grant> authorize,AuditSink audit,Clock clock,Duration timeout){
        ContextIdentity.identifier(serverId);ContextIdentity.identifier(sourceId);this.serverId=serverId;this.sourceId=sourceId;this.policies=List.copyOf(policies);
        if(policies.size()>16||policies.stream().map(ToolPolicy::alias).distinct().count()!=policies.size()||policies.stream().map(ToolPolicy::remoteName).distinct().count()!=policies.size())throw new IllegalArgumentException("Duplicate or excessive MCP registration");
        this.transport=Objects.requireNonNull(transport);this.governance=Objects.requireNonNull(governance);this.issuer=Objects.requireNonNull(issuer);this.authorize=Objects.requireNonNull(authorize);this.audit=Objects.requireNonNull(audit);this.clock=Objects.requireNonNull(clock);this.timeout=Objects.requireNonNull(timeout);
        if(timeout.toMillis()<10||timeout.toMillis()>30000)throw new IllegalArgumentException("Invalid MCP deadline");
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var thread=new Thread(r,"retail-mcp-gateway");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Session bind(TrustedCallerBoundary.Caller caller,UUID executionId){governance.runtimeAccess(caller);governance.runtimeToolSource(caller,sourceId);return new Session(caller,executionId);}
    public final class Session implements AutoCloseable {
        private final TrustedCallerBoundary.Caller caller;private final UUID executionId;
        private final Map<String,Map<String,Object>> completed=new LinkedHashMap<>();
        private final List<Evidence> evidence=new CopyOnWriteArrayList<>();
        private final Set<Future<?>> pending=ConcurrentHashMap.newKeySet();private volatile boolean closed;
        private Set<String> discoveredAliases;
        private Session(TrustedCallerBoundary.Caller caller,UUID executionId){this.caller=caller;this.executionId=executionId;}
        private ContextIdentity.Access require(ToolPolicy policy){
            var access=governance.runtimeAccess(caller);
            if(closed||access.sessionId()==null||access.taskId()==null||policy.classification().ordinal()>caller.disclosure().ordinal()||!authorize.test(new Grant(access.identity().tenantId(),access.identity().userId(),access.sessionId(),access.taskId(),policy.alias())))throw new SecurityException("MCP operation not authorized");
            return access;
        }
        private McpDelegation.Credential credential(String tool,UUID request){var a=governance.runtimeAccess(caller);return issuer.issue(new McpDelegation.Claims(serverId,a.identity().tenantId(),a.identity().userId(),tool,request,clock.instant().plusSeconds(60)));}
        /** Discovery is filtered and descriptions are always supplied by the trusted registration. */
        public synchronized List<BaseTool> tools(){
            var allowed=policies.stream().filter(p->{try{require(p);return true;}catch(SecurityException denied){return false;}}).toList();
            if(allowed.isEmpty()){discoveredAliases=Set.of();return List.of();}
            List<McpTransport.DiscoveredTool> discovered;
            try{discovered=bounded(()->transport.discover(credential("discovery",UUID.randomUUID()),timeout));}
            catch(Exception failure){
                governance.runtimeDenial(caller,"MCP_DISCOVERY_FAILED");
                if(failure instanceof McpTransport.AuthorizationFailure denied)throw denied;
                throw new IllegalStateException("Approved MCP discovery unavailable",failure);
            }
            var names=new HashSet<String>();for(var d:discovered)if(!names.add(d.name()))throw new SecurityException("Duplicate MCP discovery name");
            var result=new ArrayList<BaseTool>();
            for(var policy:allowed){
                var matches=discovered.stream().filter(t->t.name().equals(policy.remoteName())).toList();
                if(matches.size()!=1||!matches.get(0).inputSchema().equals(policy.discoverySchema()))throw new SecurityException("Pinned MCP tool missing or schema changed");
                result.add(new BaseTool(policy.alias(),policy.description()){
                    @Override public Optional<FunctionDeclaration> declaration(){return Optional.of(FunctionDeclaration.builder().name(policy.alias()).description(policy.description()).parametersJsonSchema(policy.schema().json()).build());}
                    @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args,ToolContext context){return Single.fromCallable(()->execute(policy.alias(),args));}
                });
            }
            discoveredAliases=result.stream().map(BaseTool::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
            return List.copyOf(result);
        }
        public boolean registered(String alias){return policies.stream().anyMatch(p->p.alias().equals(alias));}
        public Sensitivity classification(String alias){return policies.stream().filter(p->p.alias().equals(alias)).findFirst().orElseThrow().classification();}
        /** Serializes calls within one invocation; caches failures too, so ambiguous outcomes cannot be resent. */
        public synchronized Map<String,Object> execute(String alias,Map<String,Object> arguments){
            UUID requestId=UUID.randomUUID();long start=System.nanoTime();ToolPolicy policy=policies.stream().filter(p->p.alias().equals(alias)).findFirst().orElse(null);
            ContextIdentity.Access access=null;int attempts=0;String outcome="DENIED";boolean duplicate=false;
            try{
                if(policy==null)throw new SecurityException("MCP tool is not allowlisted");access=require(policy);var args=policy.schema().validate(arguments);
                if(discoveredAliases==null)tools();
                if(!discoveredAliases.contains(alias))throw new SecurityException("MCP tool was not approved during discovery");
                String key=alias+StructuredValues.json(new TreeMap<>(args));
                if(completed.containsKey(key)){duplicate=true;outcome="DUPLICATE_SUPPRESSED";return completed.get(key);}
                if(completed.size()>=64)throw new SecurityException("MCP invocation limit exceeded");
                completed.put(key,error("IN_PROGRESS_OR_UNKNOWN"));
                Map<String,Object> reply;
                int maximum=policy.safeIdempotent()?2:1;
                while(true){
                    require(policy);attempts++;
                    try{
                        var remote=bounded(()->transport.call(credential(policy.remoteName(),requestId),policy.remoteName(),args,requestId,timeout));
                        require(policy);
                        String text=String.join("\n",remote.text());
                        if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16000)throw new SecurityException("MCP evidence exceeds bound");
                        outcome=remote.error()?"REMOTE_ERROR":"SUCCESS";
                        reply=Map.of("status",outcome,"authority","UNTRUSTED_REFERENCE_DATA","evidence",text,"provenance",Map.of("server",serverId,"tool",alias,"sourceId",sourceId,"requestId",requestId.toString()));break;
                    }catch(McpTransport.ConnectionFailure failed){if(attempts<maximum)continue;outcome="CONNECTION_FAILURE";reply=error(outcome);break;}
                    catch(TimeoutException|McpTransport.TimeoutFailure timedOut){outcome="TIMEOUT_UNKNOWN";reply=error(outcome);break;}
                    catch(McpTransport.InvalidResponse invalid){outcome="MALFORMED_RESPONSE";reply=error(outcome);break;}
                    catch(CancellationException cancelled){outcome="CANCELLED_UNKNOWN";reply=error(outcome);break;}
                    catch(InterruptedException cancelled){Thread.currentThread().interrupt();outcome="CANCELLED_UNKNOWN";reply=error(outcome);break;}
                }
                completed.put(key,reply);return reply;
            }catch(SecurityException denied){
                if(denied instanceof McpTransport.AuthorizationFailure)outcome="AUTHORIZATION_FAILED";
                governance.runtimeDenial(caller,"MCP_ACCESS_POLICY");throw denied;
            }
            catch(Exception failure){
                var cause=failure.getCause();
                outcome=cause instanceof McpTransport.ConnectionFailure?"CONNECTION_FAILURE":
                        cause instanceof TimeoutException||cause instanceof McpTransport.TimeoutFailure?"TIMEOUT_UNKNOWN":"GATEWAY_FAILURE";
                return error(outcome);
            }
            finally{
                long duration=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                var a=access==null?null:access.identity();
                audit.append(new Audit(executionId,requestId,serverId,alias!=null&&alias.matches("mcp_[a-z_]{1,36}")?alias:"UNREGISTERED",a==null?null:a.tenantId(),a==null?null:a.userId(),outcome,attempts,duration));
                evidence.add(new Evidence(requestId,serverId,policy==null?"UNREGISTERED":policy.alias(),sourceId,outcome,duplicate,duration));
            }
        }
        private <T>T bounded(Callable<T> work)throws Exception{
            if(closed)throw new CancellationException();Future<T> future=workers.submit(work);pending.add(future);
            try{return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;if(failed.getCause() instanceof LinkageError linkage)throw new IllegalStateException("MCP dependency linkage failure",linkage);throw new IllegalStateException("MCP transport failed");}
            finally{future.cancel(true);pending.remove(future);}
        }
        public List<Evidence> evidence(){return List.copyOf(evidence);}
        @Override public void close(){closed=true;pending.forEach(f->f.cancel(true));}
    }
    private static Map<String,Object> error(String code){return Map.of("status",code,"authority","UNTRUSTED_REFERENCE_DATA","retryable",false);}
    @Override public void close(){workers.shutdownNow();}
}
