package com.retail.agent.context;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.*;

/** Host-only metadata export. No model output, document text, tokens, keys or tool argument/result bodies. */
public final class EnterpriseExecutionEvidence {
    private EnterpriseExecutionEvidence(){}
    public static Map<String,Object> completed(GovernedMemoryService governance,TrustedCallerBoundary.Caller caller,
            ContextAwareAdkRuntime.Result result,List<GovernanceAudit.Event> denials){
        var access=governance.runtimeAccess(caller);var manifest=result.executionManifest();
        var report=new LinkedHashMap<String,Object>();report.put("executionId",result.executionId().toString());
        report.put("scope",Map.of("tenant",access.identity().tenantId(),"user",access.identity().userId(),"session",access.sessionId(),"task",access.taskId()));
        report.put("modelCalls",result.requestCosts().size());report.put("requestCosts",result.requestCosts());report.put("contextBudget",result.context().manifest().tokenBudget());report.put("contextUsed",result.context().manifest().usedTokens());
        var kinds=new HashMap<UUID,String>();result.context().referenceData().stream().filter(b->b.memoryId()!=null).forEach(b->kinds.put(b.memoryId(),b.kind().name()));
        report.put("memorySelections",result.context().manifest().selections().stream().filter(s->s.memoryId()!=null).map(s->Map.of("id",s.memoryId().toString(),"version",s.memoryVersion(),"source",s.sourceId(),"verification",s.verification().name(),"kind",kinds.get(s.memoryId()),"authorization","READ_ALLOWED")).toList());
        report.put("contextDecisions",result.context().manifest().omissions().stream().map(o->Map.of("block",o.blockId(),"reason",o.reason())).toList());
        report.put("mcp",manifest.mcpTools().stream().map(e->Map.of("request",e.requestId().toString(),"server",e.server(),"tool",e.tool(),"source",e.sourceId(),"outcome",e.outcome(),"duplicate",e.duplicate(),"durationMillis",e.durationMillis())).toList());
        if(manifest.documents()!=null){
            report.put("documentRetrieval",manifest.documents().retrievalOperation().toString());
            report.put("documents",manifest.documents().selections().stream().map(s->{var c=s.citation();var fields=new LinkedHashMap<String,Object>();fields.put("citation",c.marker());fields.put("document",c.documentId().toString());fields.put("version",c.version());fields.put("chunk",c.chunkId().toString());fields.put("source",c.sourceId());fields.put("hash",c.contentHash());fields.put("location",Map.of("kind",c.location().kind(),"ordinal",c.location().ordinal()));fields.put("aclVersion",s.aclVersion());fields.put("model",s.modelId());fields.put("dimension",s.dimensions());fields.put("embeddingVersion",s.embeddingVersion());fields.put("score",s.score());fields.put("authorization",s.authorization());fields.put("outcome",s.outcome());return Collections.unmodifiableMap(fields);}).toList());
            report.put("documentDecisions",manifest.documents().decisions().stream().map(d->Map.of("citation",d.citation(),"reason",d.reason())).toList());
        }
        report.put("citationValidation",manifest.documents()==null?"NOT_APPLICABLE":manifest.documents().selections().stream().anyMatch(s->s.outcome().equals("INCLUDED"))?"AUTHORIZED_MARKERS_VALIDATED_NOT_ENTAILMENT":"INSUFFICIENT_EVIDENCE");
        report.put("securityDenials",denials.stream().filter(e->e.authenticationId()!=null&&e.authenticationId().equals(caller.authenticationId())).map(e->Map.of("operation",e.operation(),"outcome",e.outcome(),"reason",e.reason())).toList());
        boolean dependencyFailure=manifest.mcpTools().stream().anyMatch(e->!Set.of("SUCCESS","DUPLICATE_SUPPRESSED").contains(e.outcome()));
        report.put("status",dependencyFailure?"DEPENDENCY_FAILURE":manifest.documents()!=null&&manifest.documents().selections().stream().noneMatch(s->s.outcome().equals("INCLUDED"))?"INSUFFICIENT_EVIDENCE":"COMPLETED");
        return Collections.unmodifiableMap(report);
    }
    public static Map<String,Object> failed(UUID attempt,Throwable failure){return Map.of("attemptId",attempt.toString(),"status","FAILED","category",failure instanceof SecurityException?"SECURITY_DENIAL":failure instanceof ContextAssemblyService.BudgetExceededException?"CONTEXT_BUDGET":"EXECUTION_OR_AUDIT_FAILURE","executionId","UNAVAILABLE_IF_EXECUTION_DID_NOT_COMPLETE");}
    public static void write(Path file,Map<String,Object> report){
        if(!file.isAbsolute())throw new IllegalArgumentException("Absolute secured evidence path required");
        byte[] bytes=StructuredValues.json(report).getBytes(java.nio.charset.StandardCharsets.UTF_8);if(bytes.length>1_000_000)throw new IllegalArgumentException("Evidence export exceeds bound");
        try{Files.createDirectories(file.getParent());try(var c=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}}
        catch(Exception failure){throw new IllegalStateException("Enterprise evidence export unavailable");}
    }
}
