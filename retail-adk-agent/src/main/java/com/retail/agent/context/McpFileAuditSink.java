package com.retail.agent.context;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.*;

/** Metadata-only append-and-force audit. Host owns directory ACLs, disk monitoring, rotation and retention. */
public final class McpFileAuditSink implements SecureMcpGateway.AuditSink {
    private final Path file;
    public McpFileAuditSink(Path file){this.file=file.toAbsolutePath().normalize();try{Files.createDirectories(this.file.getParent());}catch(Exception e){throw new IllegalStateException("MCP audit directory unavailable");}}
    public synchronized void append(SecureMcpGateway.Audit e){
        var fields=new LinkedHashMap<String,Object>();fields.put("occurredAt",java.time.Instant.now().toString());fields.put("executionId",e.executionId().toString());fields.put("requestId",e.requestId().toString());fields.put("server",e.server());fields.put("tool",e.tool());fields.put("tenant",e.tenant());fields.put("user",e.user());fields.put("outcome",e.outcome());fields.put("attempts",e.attempts());fields.put("durationMillis",e.durationMillis());
        byte[] bytes=(StructuredValues.json(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);var lock=channel.lock()){
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }catch(Exception failure){throw new IllegalStateException("MCP audit append unavailable");}
    }
}
