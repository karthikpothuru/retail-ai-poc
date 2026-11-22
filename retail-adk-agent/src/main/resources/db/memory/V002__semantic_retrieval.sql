-- pgvector must first be installed by a DBA in public. Exact search only: no ANN indexes.
CREATE TABLE retail_memory.memory_embeddings (
    tenant_id text NOT NULL, user_id text NOT NULL, memory_id uuid NOT NULL, memory_version bigint NOT NULL,
    session_id text, task_id text, sensitivity integer NOT NULL CHECK(sensitivity BETWEEN 0 AND 3),
    model_id text NOT NULL CHECK(length(model_id) BETWEEN 1 AND 200),
    dimension integer NOT NULL CHECK(dimension BETWEEN 1 AND 3072),
    embedding public.vector NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    validity text NOT NULL DEFAULT 'VALID' CHECK(validity IN ('VALID','INVALID')),
    PRIMARY KEY(tenant_id,user_id,memory_id,memory_version,model_id,dimension),
    FOREIGN KEY(tenant_id,user_id,memory_id,memory_version)
        REFERENCES retail_memory.memory_versions(tenant_id,user_id,memory_id,version) ON DELETE CASCADE,
    CHECK(public.vector_dims(embedding)=dimension),
    CHECK(public.vector_norm(embedding)>0)
);
CREATE INDEX embedding_lookup ON retail_memory.memory_embeddings(tenant_id,user_id,model_id,dimension,memory_id,memory_version);

CREATE FUNCTION retail_memory.embedding_eligible(t text,u text,m uuid,n bigint) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
    SELECT EXISTS(SELECT 1 FROM retail_memory.memory_heads h JOIN retail_memory.memory_versions v
        ON (h.tenant_id,h.user_id,h.memory_id,h.current_version)=(v.tenant_id,v.user_id,v.memory_id,v.version)
        WHERE h.tenant_id=t AND h.user_id=u AND h.memory_id=m AND h.current_version=n
        AND h.lifecycle='ACTIVE' AND v.lifecycle='ACTIVE'
        AND (h.expires_at IS NULL OR h.expires_at>statement_timestamp())
        AND ((v.memory_type='SEMANTIC' AND v.verification='VERIFIED' AND v.source_origin IN ('KNOWLEDGE','TOOL_RESULT'))
          OR (v.memory_type='PROCEDURAL' AND v.verification='APPROVED' AND v.source_origin='PROCEDURE')))
$$;
ALTER TABLE retail_memory.memory_embeddings ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.memory_embeddings FORCE ROW LEVEL SECURITY;
CREATE POLICY embedding_read ON retail_memory.memory_embeddings FOR SELECT TO retail_memory_app
    USING(retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND validity='VALID'
          AND retail_memory.embedding_eligible(tenant_id,user_id,memory_id,memory_version));
CREATE POLICY embedding_insert ON retail_memory.memory_embeddings FOR INSERT TO retail_memory_app
    WITH CHECK(retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND validity='VALID'
          AND retail_memory.embedding_eligible(tenant_id,user_id,memory_id,memory_version)
          AND EXISTS(SELECT 1 FROM retail_memory.memory_versions v WHERE
              (v.tenant_id,v.user_id,v.memory_id,v.version)=(memory_embeddings.tenant_id,memory_embeddings.user_id,memory_embeddings.memory_id,memory_embeddings.memory_version)
              AND retail_memory.can_change(v.memory_type,v.verification)
              AND v.sensitivity=memory_embeddings.sensitivity
              AND v.session_id IS NOT DISTINCT FROM memory_embeddings.session_id
              AND v.task_id IS NOT DISTINCT FROM memory_embeddings.task_id));
-- Only the non-login owner can invalidate otherwise hidden historical rows through fixed trigger code.
CREATE POLICY embedding_owner ON retail_memory.memory_embeddings TO retail_memory_owner
    USING(retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity))
    WITH CHECK(retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity));
CREATE FUNCTION retail_memory.invalidate_embeddings() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF NEW.current_version IS DISTINCT FROM OLD.current_version OR NEW.lifecycle IS DISTINCT FROM OLD.lifecycle
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        UPDATE retail_memory.memory_embeddings SET validity='INVALID'
        WHERE tenant_id=OLD.tenant_id AND user_id=OLD.user_id AND memory_id=OLD.memory_id AND validity='VALID';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER invalidate_memory_vectors AFTER UPDATE ON retail_memory.memory_heads
    FOR EACH ROW EXECUTE FUNCTION retail_memory.invalidate_embeddings();
CREATE FUNCTION retail_memory.expire_embeddings() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE affected integer;
BEGIN
    UPDATE retail_memory.memory_embeddings e SET validity='INVALID' FROM retail_memory.memory_heads h
    WHERE (e.tenant_id,e.user_id,e.memory_id)=(h.tenant_id,h.user_id,h.memory_id)
      AND e.validity='VALID' AND h.expires_at<=statement_timestamp()
      AND retail_memory.can_change(h.memory_type,h.verification);
    GET DIAGNOSTICS affected=ROW_COUNT;
    RETURN affected;
END $$;
REVOKE ALL ON retail_memory.memory_embeddings FROM PUBLIC;
REVOKE ALL ON FUNCTION retail_memory.embedding_eligible(text,text,uuid,bigint),retail_memory.invalidate_embeddings(),retail_memory.expire_embeddings() FROM PUBLIC;
GRANT SELECT,INSERT ON retail_memory.memory_embeddings TO retail_memory_app;
GRANT EXECUTE ON FUNCTION retail_memory.embedding_eligible(text,text,uuid,bigint),retail_memory.expire_embeddings() TO retail_memory_app;
