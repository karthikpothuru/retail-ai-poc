-- DBA-only bootstrap. Runtime credentials must never have these privileges.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='retail_document_app') THEN
        CREATE ROLE retail_document_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='retail_document_ingest') THEN
        CREATE ROLE retail_document_ingest NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='retail_document_retention') THEN
        CREATE ROLE retail_document_retention NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    GRANT retail_document_app TO retail_document_ingest;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname IN ('retail_document_app','retail_document_ingest','retail_document_retention')
        AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolcanlogin))
        OR pg_has_role('retail_document_app','retail_document_ingest','MEMBER')
        OR pg_has_role('retail_document_app','retail_document_retention','MEMBER')
        OR pg_has_role('retail_document_ingest','retail_document_retention','MEMBER')
        OR pg_has_role('retail_document_retention','retail_document_ingest','MEMBER')
        OR pg_has_role('retail_document_retention','retail_document_app','MEMBER') THEN
        RAISE EXCEPTION 'Unsafe document role configuration';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'retail_memory_audit_writer') THEN
        CREATE ROLE retail_memory_audit_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'retail_memory_owner') THEN
        CREATE ROLE retail_memory_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'retail_memory_app') THEN
        CREATE ROLE retail_memory_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'retail_memory_retention') THEN
        CREATE ROLE retail_memory_retention NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('retail_memory_app','retail_memory_retention','retail_memory_audit_writer')
               AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb))
       OR pg_has_role('retail_memory_app','retail_memory_owner','MEMBER')
       OR pg_has_role('retail_memory_retention','retail_memory_owner','MEMBER')
       OR pg_has_role('retail_memory_audit_writer','retail_memory_owner','MEMBER')
       OR pg_has_role('retail_memory_audit_writer','retail_memory_app','MEMBER')
       OR pg_has_role('retail_memory_audit_writer','retail_memory_retention','MEMBER')
       OR pg_has_role('retail_memory_app','retail_memory_audit_writer','MEMBER')
       OR pg_has_role('retail_memory_retention','retail_memory_audit_writer','MEMBER') THEN
        RAISE EXCEPTION 'Unsafe pre-existing memory role configuration';
    END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS retail_memory AUTHORIZATION retail_memory_owner;
