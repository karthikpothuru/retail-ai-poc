package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class EmbeddingProviderTest {
    @Test void fakeIsDeterministicNormalizedAndSeparatesModelSpacesByDimensions() {
        var provider=new FakeEmbeddingProvider(768);
        assertThat(provider.embedDocument("inventory stock")).isEqualTo(provider.embedQuery("INVENTORY STOCK"));
        double norm=provider.embedQuery("inventory stock").values().stream().mapToDouble(v->(double)v*v).sum();
        assertThat(norm).isCloseTo(1,within(0.000001));
        assertThat(provider.embedDocument("inventory stock")).isNotEqualTo(provider.embedDocument("employee vacation"));
        assertThat(provider.embedQuery("...").values()).hasSize(768);
        assertThat(new FakeEmbeddingProvider(128).dimension()).isEqualTo(128);
    }
    @Test void dimensionMismatchNonFiniteAndZeroVectorsAreRejected() {
        assertThatThrownBy(()->new EmbeddingProvider.Vector("model",3,List.of(1f,2f))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmbeddingProvider.Vector("model",1,List.of(Float.NaN))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmbeddingProvider.Vector("model",1,List.of(Float.POSITIVE_INFINITY))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmbeddingProvider.Vector("model",2,List.of(0f,0f))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new FakeEmbeddingProvider(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void vectorsAndApplicabilityAreImmutableAndValidated() {
        var values=new java.util.ArrayList<>(List.of(1f,0f));
        var vector=new EmbeddingProvider.Vector("model",2,values);values.set(0,0f);
        assertThat(vector.values()).containsExactly(1f,0f);
        assertThatThrownBy(()->vector.values().set(0,2f)).isInstanceOf(UnsupportedOperationException.class);
        SemanticMemoryRetrieval.validateOperations(Map.of("operations",List.of("inventory.assess")));
        assertThatThrownBy(()->SemanticMemoryRetrieval.validateOperations(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SemanticMemoryRetrieval.validateOperations(Map.of("operations",List.of(7)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retrievalQueryRejectsUnboundedOrMalformedInputs() {
        assertThatThrownBy(()->new SemanticMemoryRetrieval.Query("stock","inventory",Map.of(),0,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SemanticMemoryRetrieval.Query("stock","inventory",Map.of(),10,Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SemanticMemoryRetrieval.Query("stock","invalid operation",Map.of(),10,0)).isInstanceOf(IllegalArgumentException.class);
    }
}
