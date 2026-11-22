
package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

class RetailIntentClassifierLiveTest {

    @Test
    @EnabledIfEnvironmentVariable(
            named = "RETAIL_LIVE_GEMINI_TEST",
            matches = "true"
    )
    void classifySyntheticProductQuestion() {

        String question =
                "Show me details of product ELEC-1001";

        var result = new RetailIntentClassifier()
                .classify(question);

        assertEquals(
                RetailIntentClassification.Intent.PRODUCT,
                result.intent()
        );

        assertTrue(result.modelExecuted());

        assertEquals(
                com.retail.agent.RetailInventoryAgent.MODEL_NAME,
                result.modelName()
        );
    }
}
