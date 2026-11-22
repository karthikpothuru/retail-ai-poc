
package com.retail.agent.context;

import java.util.Objects;

public record RetailIntentClassification(
        Intent intent,
        String modelName,
        boolean modelExecuted) {

    public enum Intent {
        GENERAL,
        PRODUCT,
        REGISTRATION,
        INVENTORY,
        UNKNOWN
    }

    public RetailIntentClassification {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(modelName, "modelName");
    }
}
