CREATE TABLE retail_memory.product_catalog (
    sku text PRIMARY KEY CHECK (sku ~ '^[A-Z]{2,8}-[0-9]{2,8}$'),
    product_name text NOT NULL CHECK (length(trim(product_name)) BETWEEN 1 AND 120),
    category text NOT NULL CHECK (length(trim(category)) BETWEEN 1 AND 80),
    description text NOT NULL CHECK (length(trim(description)) BETWEEN 1 AND 500),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE retail_memory.product_document_refs (
    sku text NOT NULL REFERENCES retail_memory.product_catalog(sku),
    document_id uuid PRIMARY KEY,
    source_id text NOT NULL UNIQUE CHECK (source_id ~ '^product-[a-z0-9-]{1,80}$'),
    reviewed_sha256 text NOT NULL CHECK (reviewed_sha256 ~ '^[0-9a-f]{64}$')
);

INSERT INTO retail_memory.product_catalog (sku, product_name, category, description, status) VALUES
    ('WM-101', 'Wireless Mouse', 'Pointing devices', 'Compact cordless mouse for desk use.', 'ACTIVE'),
    ('KB-201', 'Mechanical Keyboard', 'Keyboards', 'Wired compact keyboard for desktop typing.', 'ACTIVE'),
    ('MN-301', 'Computer Monitor', 'Displays', 'Desktop display for everyday desk work.', 'ACTIVE');

INSERT INTO retail_memory.product_document_refs (sku, document_id, source_id, reviewed_sha256) VALUES
    ('WM-101', '7c000000-0000-4000-8000-000000000101', 'product-wm-101', '1d3344986b9ef239036b149618773a228fd12740ef5986d7d02218f8a0d7348f'),
    ('KB-201', '7c000000-0000-4000-8000-000000000201', 'product-kb-201', 'fc2ef8a90309cf82bfd39c85691c72d0bdd1d6ccd8790b5d827029fad1157aa4'),
    ('MN-301', '7c000000-0000-4000-8000-000000000301', 'product-mn-301', 'ac15dce319fc4299d7318bb12d27f9d75937100cf377e3e3591d47b1f02a6871');

ALTER TABLE retail_memory.product_catalog ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.product_catalog FORCE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.product_document_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.product_document_refs FORCE ROW LEVEL SECURITY;

CREATE POLICY catalog_owner ON retail_memory.product_catalog TO retail_memory_owner USING (true) WITH CHECK (true);
CREATE POLICY catalog_refs_owner ON retail_memory.product_document_refs TO retail_memory_owner USING (true) WITH CHECK (true);

CREATE POLICY catalog_reader ON retail_memory.product_catalog FOR SELECT TO retail_document_app
    USING (current_setting('retail.tenant_id', true) = 'public-product-knowledge'
       AND current_setting('retail.user_id', true) IN ('anonymous-product-reader', 'product-knowledge-publisher')
       AND status = 'ACTIVE');
CREATE POLICY catalog_refs_reader ON retail_memory.product_document_refs FOR SELECT TO retail_document_app
    USING (EXISTS (SELECT 1 FROM retail_memory.product_catalog p WHERE p.sku = product_document_refs.sku));

GRANT SELECT ON retail_memory.product_catalog, retail_memory.product_document_refs TO retail_document_app;
