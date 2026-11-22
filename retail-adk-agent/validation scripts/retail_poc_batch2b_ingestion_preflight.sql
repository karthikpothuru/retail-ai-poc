-- Retail AI POC Batch 2B: read-only ingestion preflight
-- Run against retail_customer using DBeaver (Execute SQL Script / Alt+X).
-- No DDL, DML, SET ROLE or session context changes.

-- Result 1: ingestion-role table privileges (including inherited effective privileges).
SELECT t.table_name,
       has_table_privilege('retail_document_ingest', format('retail_memory.%I', t.table_name), 'SELECT') AS can_select,
       has_table_privilege('retail_document_ingest', format('retail_memory.%I', t.table_name), 'INSERT') AS can_insert,
       has_table_privilege('retail_document_ingest', format('retail_memory.%I', t.table_name), 'UPDATE') AS can_update,
       has_table_privilege('retail_document_ingest', format('retail_memory.%I', t.table_name), 'DELETE') AS can_delete
FROM (VALUES ('documents'),('document_versions'),('document_chunks'),('document_embeddings'),('document_acls'),('document_audit')) AS t(table_name)
ORDER BY t.table_name;

-- Result 2: role membership and inheritance relevant to effective grants.
SELECT r.rolname AS role_name, r.rolcanlogin, r.rolinherit, r.rolbypassrls,
       pg_has_role('retail_document_ingest', r.oid, 'MEMBER') AS ingest_is_member
FROM pg_roles r
WHERE r.rolname IN ('retail_document_ingest','retail_document_app','retail_memory_owner','retail_customer_app')
ORDER BY r.rolname;

-- Result 3: triggers and foreign keys affecting ingestion order.
SELECT c.relname AS table_name, tg.tgname AS trigger_name,
       pg_get_triggerdef(tg.oid) AS definition
FROM pg_trigger tg JOIN pg_class c ON c.oid=tg.tgrelid
JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='retail_memory' AND c.relname IN ('documents','document_versions','document_chunks','document_embeddings','document_acls')
AND NOT tg.tgisinternal
ORDER BY c.relname,tg.tgname;

-- Result 4: permissions on document functions for ingestion role.
SELECT p.proname AS function_name,
       pg_get_function_identity_arguments(p.oid) AS arguments,
       has_function_privilege('retail_document_ingest',p.oid,'EXECUTE') AS ingest_can_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='retail_memory' AND p.proname IN
('document_tenant','document_owner','document_visible','document_current','invalidate_document_vectors','document_delete_guard')
ORDER BY p.proname;

-- Result 5: confirm still empty, using administrator connection (RLS may filter for app roles).
SELECT 'documents' AS table_name, count(*) AS rows FROM retail_memory.documents
UNION ALL SELECT 'document_versions', count(*) FROM retail_memory.document_versions
UNION ALL SELECT 'document_chunks', count(*) FROM retail_memory.document_chunks
UNION ALL SELECT 'document_embeddings', count(*) FROM retail_memory.document_embeddings
UNION ALL SELECT 'document_acls', count(*) FROM retail_memory.document_acls
UNION ALL SELECT 'document_audit', count(*) FROM retail_memory.document_audit;
