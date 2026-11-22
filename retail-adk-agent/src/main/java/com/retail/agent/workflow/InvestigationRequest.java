package com.retail.agent.workflow;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** One explicit SKU/warehouse pair per investigation; never infers identifiers from history. */
public record InvestigationRequest(String sku, String warehouseId) {
    private static final Pattern SKU = Pattern.compile("(?i)(?<![\\w-])SKU-[A-Z0-9]+(?:-[A-Z0-9]+)*(?![\\w-])");
    private static final Pattern WAREHOUSE = Pattern.compile("(?i)(?<![\\w-])WH-[A-Z0-9]+(?:-[A-Z0-9]+)*(?![\\w-])");

    public InvestigationRequest {
        sku = normalize(sku);
        warehouseId = normalize(warehouseId);
        if (!SKU.matcher(sku).matches() || !WAREHOUSE.matcher(warehouseId).matches()) {
            throw new IllegalArgumentException("Please provide a complete SKU and warehouse ID, "
                    + "for example SKU-ELEC-1001 and WH-CENTRAL-01.");
        }
    }

    public static InvestigationRequest parse(String prompt) {
        var skus = identifiers(SKU, prompt);
        var warehouses = identifiers(WAREHOUSE, prompt);
        if (skus.size() != 1 || warehouses.size() != 1) {
            throw new IllegalArgumentException("Please specify exactly one SKU and one warehouse ID "
                    + "for this investigation (for example SKU-ELEC-1001 and WH-CENTRAL-01).");
        }
        return new InvestigationRequest(skus.iterator().next(), warehouses.iterator().next());
    }

    private static Set<String> identifiers(Pattern pattern, String prompt) {
        Set<String> values = new LinkedHashSet<>();
        pattern.matcher(prompt == null ? "" : prompt).results()
                .forEach(match -> values.add(normalize(match.group())));
        return values;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
