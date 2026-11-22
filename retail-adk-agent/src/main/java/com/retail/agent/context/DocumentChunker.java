package com.retail.agent.context;

import java.util.*;
import static com.retail.agent.context.EnterpriseDocument.*;

public record DocumentChunker(int size,int overlap) {
    public DocumentChunker{if(size<32||size>8000||overlap<0||overlap>=size/2)throw new IllegalArgumentException("Invalid document chunk bounds");}
    public List<Chunk> chunks(String tenant,UUID document,long version,Extracted extracted){
        ContextIdentity.identifier(tenant);if(version<1)throw new IllegalArgumentException("Invalid document version");var chunks=new ArrayList<Chunk>();
        for(var segment:extracted.segments())for(int start=0;start<segment.text().length();){
            int end=Math.min(start+size,segment.text().length());if(end<segment.text().length()&&Character.isHighSurrogate(segment.text().charAt(end-1)))end--;
            String text=segment.text().substring(start,end),hash=hash(text);
            UUID id=UUID.nameUUIDFromBytes((tenant+":"+document+":"+version+":"+segment.location()+":"+start+":"+hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            chunks.add(new Chunk(id,chunks.size(),segment.location(),start,end,text,hash));if(chunks.size()>1000)throw new IllegalArgumentException("Document chunk limit exceeded");
            if(end==segment.text().length())break;int next=end-overlap;if(Character.isLowSurrogate(segment.text().charAt(next)))next++;start=next;
        }return List.copyOf(chunks);
    }
}
