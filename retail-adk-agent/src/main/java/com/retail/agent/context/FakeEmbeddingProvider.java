package com.retail.agent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic lexical feature hashing for tests/demo only; it is not a learned semantic model. */
public final class FakeEmbeddingProvider implements EmbeddingProvider {
    private final int dimensions;
    public FakeEmbeddingProvider(int dimensions) {
        if(dimensions<8||dimensions>3072)throw new IllegalArgumentException("Fake dimension must be 8..3072");
        this.dimensions=dimensions;
    }
    @Override public String modelId(){return "fake-sha256-features-v1";}
    @Override public int dimension(){return dimensions;}
    @Override public Vector embedDocument(String content){return embed(content);}
    @Override public Vector embedQuery(String query){return embed(query);}
    private Vector embed(String text) {
        StructuredValues.content(text);
        try {
            Float[] result=new Float[dimensions];Arrays.fill(result,0f);
            var digest=MessageDigest.getInstance("SHA-256");
            var matches=Pattern.compile("[\\p{L}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS).matcher(text.toLowerCase(Locale.ROOT));
            boolean found=false;
            while(matches.find()) {
                byte[] hash=digest.digest(matches.group().getBytes(StandardCharsets.UTF_8));
                int slot=Math.floorMod(java.nio.ByteBuffer.wrap(hash).getInt(),dimensions);
                result[slot]+=1;found=true;
            }
            if(!found)result[0]=1f;
            return new Vector(modelId(),dimensions,Arrays.asList(result)).normalized();
        }catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("SHA-256 unavailable",failure);}
    }
}
