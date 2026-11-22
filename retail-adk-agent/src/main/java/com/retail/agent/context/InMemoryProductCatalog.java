
package com.retail.agent.context;

import java.util.List;
import java.util.Objects;

/**
 * Local POC product catalog.
 *
 * Uses synthetic product data only.
 * No database connection is required.
 */
public final class InMemoryProductCatalog implements ProductCatalog {

    private final List<Product> products;

    public InMemoryProductCatalog(List<Product> products) {
        this.products = List.copyOf(
                Objects.requireNonNull(products));
    }

    public static InMemoryProductCatalog demo() {
        return new InMemoryProductCatalog(List.of(
            new Product(
                "ELEC-1001",
                "Wireless Headphones",
                "Electronics",
                "Synthetic demonstration product.",
                Status.ACTIVE,
                List.of()
            ),
            new Product(
                "HOME-1002",
                "Smart LED Lamp",
                "Home Appliances",
                "Synthetic demonstration product.",
                Status.ACTIVE,
                List.of()
            )
        ));
    }

    @Override
    public List<Product> active(ContextIdentity.Access access) {
        Objects.requireNonNull(access);

        return products.stream()
                .filter(product ->
                    product.status() == Status.ACTIVE)
                .toList();
    }
}
