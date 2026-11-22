
package com.retail.agent.context;

public final class RetailIntentClassifierSmokeRunner {

    public static void main(String[] args) {

        RetailIntentClassifier classifier =
                new RetailIntentClassifier();

        String[] questions = {
                "What can you help me with as a retail shopping assistant?",
                "Show me details of product ELEC-1001",
                "How do I register a customer account?",
                "Is SKU-ELEC-1001 available in warehouse WH-CENTRAL-01?"
        };

        for (String question : questions) {

            System.out.println("\nQuestion: " + question);

            try {
                RetailIntentClassification result =
                        classifier.classify(question);

                System.out.println("Intent        : " + result.intent());
                System.out.println("Model         : " + result.modelName());
                System.out.println("Model executed: " + result.modelExecuted());

            } catch (Exception error) {
                System.err.println(
                        "Classification failed: "
                        + error.getClass().getSimpleName()
                        + ": " + error.getMessage());
            }
        }
    }
}
