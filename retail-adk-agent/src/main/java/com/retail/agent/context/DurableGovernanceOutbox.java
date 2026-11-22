package com.retail.agent.context;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

/** Metadata-only forced local journal. Operator must secure the directory ACL and replay/monitor it. */
public final class DurableGovernanceOutbox implements GovernanceAudit {
    private final Path directory;
    private final GovernanceAudit destination;
    public DurableGovernanceOutbox(Path directory,GovernanceAudit destination){
        this.directory=directory.toAbsolutePath().normalize();this.destination=destination;
        try{Files.createDirectories(this.directory);}catch(Exception e){throw new IllegalStateException("Cannot initialize audit journal",e);}
    }
    public synchronized void independent(Event event,ContextIdentity.Access access){
        if(event.outcome().equals("SUCCESS"))throw new IllegalArgumentException("Denial journal only");
        try{destination.independent(event,access);return;}catch(RuntimeException unavailable){/* journal before returning */}
        String text=String.join("\t",event.eventId().toString(),event.occurredAt().toString(),value(event.tenantId()),value(event.userId()),value(event.authenticationId()),event.operation(),event.outcome(),event.reason());
        try(var channel=FileChannel.open(directory.resolve(event.eventId()+".event"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var bytes=ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }catch(Exception failure){throw new IllegalStateException("Both database audit and durable journal unavailable",failure);}
    }
    public synchronized int replay(){
        int count=0;
        try(var paths=Files.list(directory)){
            for(var path:paths.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}\\.event")).sorted().toList()){
                String[] f=Files.readString(path).split("\t",-1);
                if(f.length!=8)throw new IllegalStateException("Invalid audit journal entry");
                var event=new Event(UUID.fromString(f[0]),Instant.parse(f[1]),nullable(f[2]),nullable(f[3]),f[4].isEmpty()?null:UUID.fromString(f[4]),f[5],f[6],f[7],null,null);
                destination.independent(event,null);Files.delete(path);count++;
            }
            return count;
        }catch(Exception failure){throw new IllegalStateException("Audit replay incomplete; entries retained",failure);}
    }
    private static String value(Object value){return value==null?"":value.toString();}
    private static String nullable(String value){return value.isEmpty()?null:value;}
}
