package com.retail.agent.context;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;

/** Local trusted-host exercise for the consent gate. No model or tool invocation. */
public final class CustomerMemoryCliRunner {
    private CustomerMemoryCliRunner() {}

    public static void main(String[] args) {
        boolean read = args.length == 1 && args[0].equals("read");
        boolean remember = args.length == 2 && args[0].equals("remember");
        if (!read && !remember) {
            throw new IllegalArgumentException("Use read or remember concise|detailed");
        }
        var level = remember ? ConsentAwareCustomerMemory.DetailLevel.valueOf(args[1].toUpperCase(Locale.ROOT)) : null;
        if (!"postgres".equals(System.getenv("MEMORY_REPOSITORY"))) {
            throw new IllegalArgumentException("Configure the existing PostgreSQL memory backend for the CLI");
        }
        var cookieValue = System.getenv("RETAIL_DEMO_SESSION");
        if (cookieValue == null || cookieValue.isBlank()) {
            System.out.println(ConsentAwareCustomerMemory.Status.NO_SESSION);
            return;
        }

        var clock = Clock.systemUTC();
        var audit = new ContextAuditLog(clock);
        try (var repository = MemoryRepositoryProvider.open(clock, ConsentAwareCustomerMemory.writePolicy(clock), audit);
             var auditPool = PostgresGovernanceAudit.writerPool(System::getenv)) {
            var origin = System.getenv().getOrDefault("RETAIL_CUSTOMER_CONTEXT_URL", "http://127.0.0.1:8081");
            var memory = new ConsentAwareCustomerMemory(new DemoCustomerContextClient(URI.create(origin)),
                    repository.repository(), new PostgresGovernanceAudit(auditPool), clock);
            var cookie = new DemoCustomerContextClient.SessionCookie(cookieValue);
            var result = read ? memory.read(cookie) : memory.remember(cookie, level);
            System.out.println(result.status());
            result.memory().forEach(view -> System.out.println(view.content()));
        } catch (RuntimeException failure) {
            System.out.println(ConsentAwareCustomerMemory.Status.UNAVAILABLE);
        }
    }
}
