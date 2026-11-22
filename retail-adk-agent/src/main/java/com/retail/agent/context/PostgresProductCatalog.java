package com.retail.agent.context;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Catalog reads share the existing document pool and transaction-local scope binding. */
public final class PostgresProductCatalog implements ProductCatalog {
    private final DataSource reader;

    public PostgresProductCatalog(DataSource reader) {
        PostgresTransactions.requireRole(reader, "retail_document_app");
        this.reader = reader;
    }

    @Override
    public List<Product> active(ContextIdentity.Access access) {
        return PostgresTransactions.inScope(reader, access, connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT p.sku, p.product_name, p.category, p.description, p.status,
                           r.document_id, r.source_id, r.reviewed_sha256
                    FROM retail_memory.product_catalog p
                    LEFT JOIN retail_memory.product_document_refs r USING (sku)
                    WHERE p.status = 'ACTIVE'
                    ORDER BY p.sku, r.source_id
                    LIMIT 1001
                    """); var rows = statement.executeQuery()) {
                var products = new ArrayList<Product>();
                var refs = new ArrayList<DocumentRef>();
                Product current = null;
                int count = 0;
                while (rows.next()) {
                    if (++count > 1000) {
                        throw new IllegalStateException("Product catalog exceeds the POC bound");
                    }
                    String sku = rows.getString("sku");
                    if (current == null || !current.sku().equals(sku)) {
                        if (current != null) {
                            products.add(withRefs(current, refs));
                        }
                        refs.clear();
                        current = new Product(sku, rows.getString("product_name"), rows.getString("category"),
                                rows.getString("description"), Status.valueOf(rows.getString("status")), List.of());
                    }
                    var document = rows.getObject("document_id", UUID.class);
                    if (document != null) {
                        refs.add(new DocumentRef(document, rows.getString("source_id"), rows.getString("reviewed_sha256")));
                    }
                }
                if (current != null) {
                    products.add(withRefs(current, refs));
                }
                return List.copyOf(products);
            }
        });
    }

    private static Product withRefs(Product product, List<DocumentRef> refs) {
        return new Product(product.sku(), product.name(), product.category(), product.description(), product.status(), refs);
    }
}
