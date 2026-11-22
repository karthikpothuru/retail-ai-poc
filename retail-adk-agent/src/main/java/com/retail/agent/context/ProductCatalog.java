package com.retail.agent.context;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Trusted catalog adapter. Product status describes the listing lifecycle. */
@FunctionalInterface
public interface ProductCatalog {
    Pattern SKU = Pattern.compile("[A-Z]{2,8}-[0-9]{2,8}");

    enum Status { ACTIVE, INACTIVE }

    record DocumentRef(UUID documentId, String sourceId, String reviewedSha256) {
        public DocumentRef {
            java.util.Objects.requireNonNull(documentId);
            if (!sourceId.matches("product-[a-z0-9-]{1,80}") || !reviewedSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid product document reference");
            }
        }
    }

    record Product(String sku, String name, String category, String description, Status status,
                   List<DocumentRef> approvedDocuments) {
        public Product {
            if (!SKU.matcher(sku).matches() || name.isBlank() || category.isBlank() || description.isBlank()) {
                throw new IllegalArgumentException("Invalid product row");
            }
            java.util.Objects.requireNonNull(status);
            approvedDocuments = List.copyOf(approvedDocuments);
        }
    }

    List<Product> active(ContextIdentity.Access access);
}
