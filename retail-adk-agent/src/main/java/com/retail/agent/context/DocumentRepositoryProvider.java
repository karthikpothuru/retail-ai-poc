package com.retail.agent.context;

import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import java.util.function.Function;

/** Separate document configuration. Existing memory selection and pools are never changed. */
public final class DocumentRepositoryProvider {
    private DocumentRepositoryProvider(){}
    public record Handle(DocumentRepository repository,List<HikariDataSource> pools) implements AutoCloseable{
        public Handle{pools=List.copyOf(pools);}
        public void close(){pools.forEach(HikariDataSource::close);}
    }
    public static Handle open(){return open(System::getenv);}
    static Handle open(Function<String,String> env){
        String selection=Optional.ofNullable(env.apply("DOCUMENT_REPOSITORY")).orElse("in-memory");
        if(selection.equals("in-memory"))return new Handle(new InMemoryDocumentRepository(),List.of());
        if(!selection.equals("postgres"))throw new IllegalArgumentException("Unknown document repository selection");
        var pools=new ArrayList<HikariDataSource>();
        try{
            var reader=pool(env,"READER");pools.add(reader);HikariDataSource writer=null,retention=null;
            if(env.apply("DOCUMENT_POSTGRES_INGEST_URL")!=null){writer=pool(env,"INGEST");pools.add(writer);}
            if(env.apply("DOCUMENT_POSTGRES_RETENTION_URL")!=null){retention=pool(env,"RETENTION");pools.add(retention);}
            return new Handle(new PostgresDocumentRepository(reader,writer,retention),pools);
        }catch(RuntimeException failure){pools.forEach(HikariDataSource::close);throw failure;}
    }
    private static HikariDataSource pool(Function<String,String> env,String role){return MemoryRepositoryProvider.pool(key->switch(key){
        case "MEMORY_POSTGRES_URL" -> env.apply("DOCUMENT_POSTGRES_"+role+"_URL");
        case "MEMORY_POSTGRES_USER" -> env.apply("DOCUMENT_POSTGRES_"+role+"_USER");
        case "MEMORY_POSTGRES_PASSWORD" -> env.apply("DOCUMENT_POSTGRES_"+role+"_PASSWORD");
        default -> env.apply(key);
    },false);}
}
