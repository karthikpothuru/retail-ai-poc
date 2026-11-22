-- Separate document domain, exact vector search. Existing memory tables and policies are untouched.
CREATE TABLE retail_memory.documents (
 tenant_id text NOT NULL, document_id uuid NOT NULL, owner_user_id text NOT NULL,
 source_id text NOT NULL, source_system text NOT NULL,
 current_version bigint NOT NULL CHECK(current_version>0), acl_version bigint NOT NULL CHECK(acl_version>0),
 classification integer NOT NULL CHECK(classification BETWEEN 0 AND 3),
 status text NOT NULL CHECK(status IN ('ACTIVE','REVOKED','DELETED')),
 ingested_at timestamptz NOT NULL, expires_at timestamptz,
 legal_hold boolean NOT NULL DEFAULT false, retain_until timestamptz,
 PRIMARY KEY(tenant_id,document_id)
);
CREATE TABLE retail_memory.document_versions (
 tenant_id text NOT NULL, document_id uuid NOT NULL, version bigint NOT NULL CHECK(version>0),
 mime text NOT NULL CHECK(mime IN ('text/plain','text/markdown','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document')),
 content_hash text NOT NULL CHECK(content_hash ~ '^[a-f0-9]{64}$'), source_modified_at timestamptz NOT NULL, ingested_at timestamptz NOT NULL,
 state text NOT NULL CHECK(state IN ('READY','SUPERSEDED')),
 PRIMARY KEY(tenant_id,document_id,version),
 FOREIGN KEY(tenant_id,document_id) REFERENCES retail_memory.documents ON DELETE CASCADE
);
ALTER TABLE retail_memory.documents ADD CONSTRAINT document_current_version FOREIGN KEY(tenant_id,document_id,current_version)
 REFERENCES retail_memory.document_versions DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE retail_memory.document_chunks (
 tenant_id text NOT NULL, document_id uuid NOT NULL, version bigint NOT NULL, chunk_id uuid NOT NULL,
 position integer NOT NULL CHECK(position>=0), location_kind text NOT NULL CHECK(location_kind IN ('page','paragraph','section')),
 location_ordinal integer NOT NULL CHECK(location_ordinal>0), section text NOT NULL,
 start_offset integer NOT NULL CHECK(start_offset>=0), end_offset integer NOT NULL,
 content text NOT NULL CHECK(length(content) BETWEEN 1 AND 8000), text_hash text NOT NULL CHECK(text_hash ~ '^[a-f0-9]{64}$'),
 PRIMARY KEY(tenant_id,document_id,version,chunk_id), UNIQUE(tenant_id,document_id,version,position),
 CHECK(end_offset>start_offset),
 FOREIGN KEY(tenant_id,document_id,version) REFERENCES retail_memory.document_versions ON DELETE CASCADE
);
CREATE TABLE retail_memory.document_embeddings (
 tenant_id text NOT NULL, document_id uuid NOT NULL, version bigint NOT NULL, chunk_id uuid NOT NULL,
 model_id text NOT NULL CHECK(length(model_id) BETWEEN 1 AND 200), dimension integer NOT NULL CHECK(dimension BETWEEN 1 AND 3072),
 embedding_version integer NOT NULL CHECK(embedding_version=1), embedding public.vector NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), valid boolean NOT NULL DEFAULT true,
 PRIMARY KEY(tenant_id,document_id,version,chunk_id,model_id,dimension),
 CHECK(public.vector_dims(embedding)=dimension AND public.vector_norm(embedding)>0),
 FOREIGN KEY(tenant_id,document_id,version,chunk_id) REFERENCES retail_memory.document_chunks ON DELETE CASCADE
);
CREATE TABLE retail_memory.document_acls (
 tenant_id text NOT NULL, document_id uuid NOT NULL, principal_user_id text NOT NULL,
 permission text NOT NULL DEFAULT 'READ' CHECK(permission='READ'),
 PRIMARY KEY(tenant_id,document_id,principal_user_id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES retail_memory.documents ON DELETE CASCADE
);
CREATE TABLE retail_memory.document_audit (
 event_id uuid PRIMARY KEY, tenant_id text NOT NULL, user_id text NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(), action text NOT NULL CHECK(action IN ('INGEST','SEARCH','REVALIDATE','REVOKED','DELETED','ACL','RETENTION','CLEANUP')),
 document_id uuid, version bigint, affected_count integer NOT NULL CHECK(affected_count>=0)
);
CREATE INDEX document_owner ON retail_memory.documents(tenant_id,owner_user_id,status);
CREATE INDEX document_acl_principal ON retail_memory.document_acls(tenant_id,principal_user_id,document_id);
CREATE INDEX document_vector_profile ON retail_memory.document_embeddings(tenant_id,model_id,dimension,document_id,version) WHERE valid;
CREATE INDEX document_audit_scope ON retail_memory.document_audit(tenant_id,user_id,occurred_at);

CREATE FUNCTION retail_memory.document_tenant(t text) RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT t=nullif(current_setting('retail.tenant_id',true),'') AND nullif(current_setting('retail.user_id',true),'') IS NOT NULL
$$;
-- Fixed definer readers break RLS recursion without exposing content or accepting arbitrary SQL.
CREATE FUNCTION retail_memory.document_owner(t text,d uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT 1 FROM retail_memory.documents h WHERE h.tenant_id=t AND h.document_id=d
  AND retail_memory.document_tenant(t) AND h.owner_user_id=nullif(current_setting('retail.user_id',true),'')
  AND h.classification<=coalesce(nullif(current_setting('retail.clearance',true),''),'-1')::integer)
$$;
CREATE FUNCTION retail_memory.document_visible(t text,d uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT 1 FROM retail_memory.documents h WHERE h.tenant_id=t AND h.document_id=d
  AND retail_memory.document_tenant(t) AND h.status='ACTIVE' AND (h.expires_at IS NULL OR h.expires_at>statement_timestamp())
  AND h.classification<=coalesce(nullif(current_setting('retail.clearance',true),''),'-1')::integer
  AND (h.owner_user_id=nullif(current_setting('retail.user_id',true),'') OR EXISTS(
   SELECT 1 FROM retail_memory.document_acls a WHERE a.tenant_id=t AND a.document_id=d
    AND a.principal_user_id=nullif(current_setting('retail.user_id',true),''))))
$$;
CREATE FUNCTION retail_memory.document_current(t text,d uuid,v bigint) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT retail_memory.document_visible(t,d) AND EXISTS(SELECT 1 FROM retail_memory.documents h WHERE h.tenant_id=t AND h.document_id=d AND h.current_version=v)
$$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['documents','document_versions','document_chunks','document_embeddings','document_acls','document_audit'] LOOP
  EXECUTE format('ALTER TABLE retail_memory.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE retail_memory.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('REVOKE ALL ON retail_memory.%I FROM PUBLIC',tab);
  EXECUTE format('CREATE POLICY document_owner_internal ON retail_memory.%I TO retail_memory_owner USING(retail_memory.document_tenant(tenant_id)) WITH CHECK(retail_memory.document_tenant(tenant_id))',tab);
 END LOOP;
END $$;
CREATE POLICY document_read ON retail_memory.documents FOR SELECT TO retail_document_app USING(retail_memory.document_visible(tenant_id,document_id) OR retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_retention_read ON retail_memory.documents FOR SELECT TO retail_document_retention USING(retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_create ON retail_memory.documents FOR INSERT TO retail_document_ingest WITH CHECK(retail_memory.document_tenant(tenant_id) AND owner_user_id=current_setting('retail.user_id',true) AND classification<=current_setting('retail.clearance',true)::integer);
CREATE POLICY document_change ON retail_memory.documents FOR UPDATE TO retail_document_ingest USING(retail_memory.document_owner(tenant_id,document_id)) WITH CHECK(retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_retain ON retail_memory.documents FOR UPDATE TO retail_document_retention USING(retail_memory.document_owner(tenant_id,document_id)) WITH CHECK(retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_remove ON retail_memory.documents FOR DELETE TO retail_document_retention USING(retail_memory.document_owner(tenant_id,document_id) AND status='DELETED');
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['document_versions','document_chunks','document_embeddings'] LOOP
  EXECUTE format('CREATE POLICY current_document_read ON retail_memory.%I FOR SELECT TO retail_document_app USING(retail_memory.document_current(tenant_id,document_id,version))',tab);
  EXECUTE format('CREATE POLICY document_ingest_read ON retail_memory.%I FOR SELECT TO retail_document_ingest,retail_document_retention USING(retail_memory.document_owner(tenant_id,document_id))',tab);
  EXECUTE format('CREATE POLICY document_ingest_insert ON retail_memory.%I FOR INSERT TO retail_document_ingest WITH CHECK(retail_memory.document_owner(tenant_id,document_id))',tab);
 END LOOP;
END $$;
CREATE POLICY document_version_supersede ON retail_memory.document_versions FOR UPDATE TO retail_document_ingest USING(retail_memory.document_owner(tenant_id,document_id)) WITH CHECK(retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_version_prune ON retail_memory.document_versions FOR DELETE TO retail_document_retention USING(retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_acl_read ON retail_memory.document_acls FOR SELECT TO retail_document_app USING(retail_memory.document_tenant(tenant_id) AND (principal_user_id=current_setting('retail.user_id',true) OR retail_memory.document_owner(tenant_id,document_id)));
CREATE POLICY document_acl_write ON retail_memory.document_acls TO retail_document_ingest USING(retail_memory.document_owner(tenant_id,document_id)) WITH CHECK(retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_embedding_valid ON retail_memory.document_embeddings AS RESTRICTIVE FOR SELECT TO retail_document_app USING(valid);
CREATE POLICY document_audit_insert ON retail_memory.document_audit FOR INSERT TO retail_document_app WITH CHECK(retail_memory.document_tenant(tenant_id) AND user_id=current_setting('retail.user_id',true) AND action IN ('SEARCH','REVALIDATE'));
CREATE POLICY document_audit_ingest ON retail_memory.document_audit FOR INSERT TO retail_document_ingest WITH CHECK(retail_memory.document_tenant(tenant_id) AND user_id=current_setting('retail.user_id',true) AND action IN ('INGEST','ACL','REVOKED','DELETED') AND retail_memory.document_owner(tenant_id,document_id));
CREATE POLICY document_audit_retention ON retail_memory.document_audit FOR INSERT TO retail_document_retention WITH CHECK(retail_memory.document_tenant(tenant_id) AND user_id=current_setting('retail.user_id',true) AND action IN ('RETENTION','CLEANUP'));

CREATE FUNCTION retail_memory.invalidate_document_vectors() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.current_version IS DISTINCT FROM OLD.current_version OR NEW.status IS DISTINCT FROM OLD.status OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
  UPDATE retail_memory.document_embeddings SET valid=false WHERE tenant_id=OLD.tenant_id AND document_id=OLD.document_id AND version<>NEW.current_version OR tenant_id=OLD.tenant_id AND document_id=OLD.document_id AND NEW.status<>'ACTIVE';
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER document_invalidation AFTER UPDATE ON retail_memory.documents FOR EACH ROW EXECUTE FUNCTION retail_memory.invalidate_document_vectors();
CREATE FUNCTION retail_memory.document_delete_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE h retail_memory.documents;
BEGIN
 IF TG_TABLE_NAME='documents' THEN h=OLD; ELSE SELECT * INTO h FROM retail_memory.documents WHERE tenant_id=OLD.tenant_id AND document_id=OLD.document_id; END IF;
 IF h.legal_hold OR h.retain_until>statement_timestamp() THEN RAISE EXCEPTION 'Document retention protection';END IF;
 IF TG_TABLE_NAME='document_versions' THEN
  IF OLD.version=h.current_version AND h.status<>'DELETED' THEN RAISE EXCEPTION 'Current document version cannot be pruned';END IF;
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER document_head_delete_guard BEFORE DELETE ON retail_memory.documents FOR EACH ROW EXECUTE FUNCTION retail_memory.document_delete_guard();
CREATE TRIGGER document_version_delete_guard BEFORE DELETE ON retail_memory.document_versions FOR EACH ROW EXECUTE FUNCTION retail_memory.document_delete_guard();
REVOKE ALL ON FUNCTION retail_memory.document_tenant(text),retail_memory.document_owner(text,uuid),retail_memory.document_visible(text,uuid),retail_memory.document_current(text,uuid,bigint),retail_memory.invalidate_document_vectors(),retail_memory.document_delete_guard() FROM PUBLIC;
GRANT USAGE ON SCHEMA retail_memory TO retail_document_app,retail_document_retention;
GRANT EXECUTE ON FUNCTION retail_memory.document_tenant(text),retail_memory.document_owner(text,uuid),retail_memory.document_visible(text,uuid),retail_memory.document_current(text,uuid,bigint) TO retail_document_app;
GRANT SELECT ON retail_memory.documents,retail_memory.document_versions,retail_memory.document_chunks,retail_memory.document_embeddings,retail_memory.document_acls TO retail_document_app;
GRANT INSERT ON retail_memory.document_audit TO retail_document_app;
GRANT INSERT ON retail_memory.documents,retail_memory.document_versions,retail_memory.document_chunks,retail_memory.document_embeddings,retail_memory.document_acls TO retail_document_ingest;
GRANT UPDATE(current_version,acl_version,classification,status,ingested_at,expires_at) ON retail_memory.documents TO retail_document_ingest;
GRANT UPDATE(state) ON retail_memory.document_versions TO retail_document_ingest;
GRANT DELETE ON retail_memory.document_acls TO retail_document_ingest;
GRANT UPDATE(legal_hold,retain_until) ON retail_memory.documents TO retail_document_retention;
GRANT DELETE ON retail_memory.documents,retail_memory.document_versions TO retail_document_retention;
GRANT SELECT ON retail_memory.documents,retail_memory.document_versions TO retail_document_retention;
GRANT INSERT ON retail_memory.document_audit TO retail_document_retention;
GRANT EXECUTE ON FUNCTION retail_memory.document_tenant(text),retail_memory.document_owner(text,uuid) TO retail_document_retention;
