package com.retail.agent.context;

import com.google.genai.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="runLiveEmbeddingTests",matches="true")
class GeminiEmbeddingLiveTest {
    @Test void supportedJavaSdkReturnsRequestedDimensions() {
        try(var client=Client.builder().build()) {
            var provider=new GeminiEmbeddingProvider(client,768);
            var result=provider.embedDocument("Inventory available quantity equals on-hand minus reserved quantity.");
            assertThat(result.values()).hasSize(768);assertThat(result.modelId()).isEqualTo("gemini-embedding-001");
        }
    }
}
