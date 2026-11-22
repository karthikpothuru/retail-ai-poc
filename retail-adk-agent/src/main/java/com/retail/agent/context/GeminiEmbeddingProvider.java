package com.retail.agent.context;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;

/** Verified SDK 1.77.0 embedContent API. Explicitly constructed only; caller owns the supplied client. */
public final class GeminiEmbeddingProvider implements EmbeddingProvider {
    public static final String MODEL="gemini-embedding-001";
    private final Client client;
    private final int dimensions;
    public GeminiEmbeddingProvider(Client client,int dimensions) {
        this.client=java.util.Objects.requireNonNull(client);
        if(dimensions!=768&&dimensions!=1536&&dimensions!=3072) {
            throw new IllegalArgumentException("Supported configured Gemini embedding dimensions: 768,1536,3072");
        }
        this.dimensions=dimensions;
    }
    @Override public String modelId(){return MODEL;}
    @Override public int dimension(){return dimensions;}
    @Override public Vector embedDocument(String content){return embed(content,"RETRIEVAL_DOCUMENT");}
    @Override public Vector embedQuery(String query){return embed(query,"RETRIEVAL_QUERY");}
    private Vector embed(String content,String task) {
        StructuredValues.content(content);
        var response=client.models.embedContent(MODEL,content,EmbedContentConfig.builder()
                .taskType(task).outputDimensionality(dimensions).build());
        var embeddings=response.embeddings().orElseThrow(()->new IllegalStateException("Missing embedding response"));
        if(embeddings.size()!=1)throw new IllegalStateException("Expected one embedding response");
        return new Vector(MODEL,dimensions,embeddings.get(0).values().orElseThrow()).normalized();
    }
}
