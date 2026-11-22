
package com.retail.agent.context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

public final class RetailIntentShadow implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "retail-intent-shadow");
                thread.setDaemon(true);
                return thread;
            });

    private final Semaphore capacity = new Semaphore(1);
    private final java.util.function.Function<
        String, RetailIntentClassification> classifier;
		private final Runnable onClassificationFinished;
		
		
public RetailIntentShadow() {
    this(new RetailIntentClassifier()::classify, () -> {});
}

RetailIntentShadow(
        java.util.function.Function<
                String, RetailIntentClassification> classifier) {
    this(classifier, () -> {});
}

RetailIntentShadow(
        java.util.function.Function<
                String, RetailIntentClassification> classifier,
        Runnable onClassificationFinished) {

    this.classifier = java.util.Objects.requireNonNull(classifier);
    this.onClassificationFinished =
            java.util.Objects.requireNonNull(onClassificationFinished);
}



public void observe(String question, java.util.function.BooleanSupplier authorized) {

    java.util.Objects.requireNonNull(authorized);

    var safeQuestion = RetailIntentInputFilter.sanitize(question);

    if (safeQuestion.isEmpty() || !capacity.tryAcquire()) {
        return;
    }

    try {
        executor.execute(() -> {
            try {
                // Revalidate authorization inside the worker.
                // Any exception must prevent model invocation.
                if (!authorized.getAsBoolean()) {
                    return;
                }

                var result = classifier.apply(safeQuestion.get());

                System.out.println(
                        "[ADK-INTENT] intent=" + result.intent()
                        + " model=" + result.modelName()
                        + " executed=" + result.modelExecuted());

            } catch (RuntimeException failure) {
                System.err.println(
                        "[ADK-INTENT] classification skipped: "
                        + failure.getClass().getSimpleName());
            } finally {
                capacity.release();
                onClassificationFinished.run();
            }
        });
    } catch (RejectedExecutionException rejected) {
        capacity.release();
    }
}


    @Override
    public void close() {
        executor.shutdownNow();
    }
}
