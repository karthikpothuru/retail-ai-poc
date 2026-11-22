package com.retail.agent.context;

import java.util.List;

/** Document/query separation is part of the embedding model contract, not the Gemini chat model. */
public interface EmbeddingProvider {
    String modelId();
    int dimension();
    Vector embedDocument(String content);
    Vector embedQuery(String query);

    record Vector(String modelId,int dimension,List<Float> values) {
        public Vector {
            if(modelId==null||!modelId.matches("[A-Za-z0-9_./:-]{1,200}")||dimension<1||dimension>3072) {
                throw new IllegalArgumentException("Invalid embedding model configuration");
            }
            values=List.copyOf(values);
            if(values.size()!=dimension) { throw new IllegalArgumentException("Embedding dimension mismatch"); }
            double norm=0;
            for(float value:values) { if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite embedding");norm+=(double)value*value; }
            if(!Double.isFinite(norm)||norm==0)throw new IllegalArgumentException("Zero or invalid embedding norm");
        }
        public Vector normalized() {
            double norm=Math.sqrt(values.stream().mapToDouble(value->(double)value*value).sum());
            return new Vector(modelId,dimension,values.stream().map(value->(float)(value/norm)).toList());
        }
        String sqlLiteral() { return values.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",","[","]")); }
    }
}
