CREATE TABLE retail_memory.memory_heads (
    tenant_id text NOT NULL, user_id text NOT NULL, memory_id uuid NOT NULL,
    session_id text, task_id text, memory_type text NOT NULL CHECK (memory_type IN ('WORKING','EPISODIC','SEMANTIC','PROCEDURAL')),
    current_version bigint NOT NULL CHECK (current_version > 0),
    verification text NOT NULL CHECK (verification IN ('CANDIDATE','VERIFIED','APPROVED')),
    sensitivity integer NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    lifecycle text NOT NULL CHECK (lifecycle IN ('ACTIVE','REVOKED')),
    expires_at timestamptz,
    legal_hold boolean NOT NULL DEFAULT false, retain_until timestamptz,
    retention_policy text NOT NULL DEFAULT 'explicit-delete-or-expired',
    PRIMARY KEY (tenant_id,user_id,memory_id),
    CHECK (memory_type <> 'WORKING' OR session_id IS NOT NULL OR task_id IS NOT NULL)
);
CREATE TABLE retail_memory.memory_versions (
    tenant_id text NOT NULL, user_id text NOT NULL, memory_id uuid NOT NULL, version bigint NOT NULL CHECK (version > 0),
    session_id text, task_id text, memory_type text NOT NULL CHECK (memory_type IN ('WORKING','EPISODIC','SEMANTIC','PROCEDURAL')),
    content text NOT NULL CHECK (length(content) BETWEEN 1 AND 12000),
    metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    source_id text NOT NULL, source_tenant_id text NOT NULL, source_user_id text,
    source_origin text NOT NULL CHECK (source_origin IN ('USER_INPUT','CONVERSATION','RETRIEVED_EVIDENCE','TOOL_RESULT','KNOWLEDGE','PROCEDURE','SYSTEM','POLICY')),
    source_citation text NOT NULL,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
    verification text NOT NULL CHECK (verification IN ('CANDIDATE','VERIFIED','APPROVED')),
    sensitivity integer NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    supersedes_version bigint,
    lifecycle text NOT NULL CHECK (lifecycle IN ('ACTIVE','REVOKED','SUPERSEDED')),
    PRIMARY KEY (tenant_id,user_id,memory_id,version),
    FOREIGN KEY (tenant_id,user_id,memory_id) REFERENCES retail_memory.memory_heads ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,user_id,memory_id,supersedes_version) REFERENCES retail_memory.memory_versions,
    CHECK ((version = 1 AND supersedes_version IS NULL) OR supersedes_version = version - 1),
    CHECK (updated_at >= created_at),
    CHECK (source_tenant_id = tenant_id AND (source_user_id IS NULL OR source_user_id = user_id)),
    CHECK (memory_type NOT IN ('WORKING','EPISODIC') OR verification = 'CANDIDATE'),
    CHECK (memory_type <> 'SEMANTIC' OR verification = 'CANDIDATE'
           OR (verification = 'VERIFIED' AND source_origin IN ('KNOWLEDGE','TOOL_RESULT'))),
    CHECK (memory_type <> 'PROCEDURAL' OR (verification = 'APPROVED' AND source_origin = 'PROCEDURE'))
);
ALTER TABLE retail_memory.memory_heads ADD CONSTRAINT current_revision_fk
    FOREIGN KEY (tenant_id,user_id,memory_id,current_version) REFERENCES retail_memory.memory_versions
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE retail_memory.audit_events (
    event_id uuid PRIMARY KEY, tenant_id text NOT NULL, user_id text NOT NULL,
    session_id text, task_id text, occurred_at timestamptz NOT NULL,
    action text NOT NULL, outcome text NOT NULL, memory_id uuid, memory_version bigint,
    affected_count integer NOT NULL CHECK (affected_count >= 0)
    -- Deliberately no cascading FK: deletion leaves content-free audit evidence.
);
CREATE INDEX memory_head_scope ON retail_memory.memory_heads (tenant_id,user_id,session_id,task_id);
CREATE INDEX memory_head_expiry ON retail_memory.memory_heads (tenant_id,user_id,expires_at) WHERE NOT legal_hold;
CREATE INDEX memory_version_search ON retail_memory.memory_versions (tenant_id,user_id,updated_at DESC,memory_id);
CREATE INDEX memory_audit_scope ON retail_memory.audit_events (tenant_id,user_id,occurred_at);

CREATE FUNCTION retail_memory.allowed(t text,u text,s text,k text,c integer) RETURNS boolean
LANGUAGE sql STABLE SET search_path = pg_catalog AS $$
    SELECT t = nullif(current_setting('retail.tenant_id',true),'')
       AND u = nullif(current_setting('retail.user_id',true),'')
       AND (s IS NULL OR s = nullif(current_setting('retail.session_id',true),''))
       AND (k IS NULL OR k = nullif(current_setting('retail.task_id',true),''))
       AND c <= coalesce(nullif(current_setting('retail.clearance',true),'')::integer,-1)
$$;
CREATE FUNCTION retail_memory.can_change(kind text,trust text) RETURNS boolean
LANGUAGE sql STABLE SET search_path = pg_catalog AS $$
    SELECT coalesce(nullif(current_setting('retail.capabilities',true),'')::jsonb,'[]'::jsonb) ? 'WRITE_MEMORY'
       AND (kind <> 'PROCEDURAL' OR coalesce(nullif(current_setting('retail.capabilities',true),'')::jsonb,'[]'::jsonb) ? 'APPROVE_PROCEDURE')
       AND (kind <> 'SEMANTIC' OR trust <> 'VERIFIED'
            OR coalesce(nullif(current_setting('retail.capabilities',true),'')::jsonb,'[]'::jsonb) ? 'VERIFY_SEMANTIC')
$$;
ALTER TABLE retail_memory.memory_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.memory_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.memory_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.memory_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY head_read ON retail_memory.memory_heads FOR SELECT USING (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity));
CREATE POLICY head_insert ON retail_memory.memory_heads FOR INSERT WITH CHECK
    (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification));
CREATE POLICY head_update ON retail_memory.memory_heads FOR UPDATE
    USING (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification))
    WITH CHECK (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification));
CREATE POLICY head_delete ON retail_memory.memory_heads FOR DELETE USING
    (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification));
CREATE POLICY version_read ON retail_memory.memory_versions FOR SELECT USING (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity));
CREATE POLICY version_insert ON retail_memory.memory_versions FOR INSERT WITH CHECK
    (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification));
CREATE POLICY version_update ON retail_memory.memory_versions FOR UPDATE
    USING (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification))
    WITH CHECK (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity) AND retail_memory.can_change(memory_type,verification));
CREATE POLICY audit_read ON retail_memory.audit_events FOR SELECT USING (retail_memory.allowed(tenant_id,user_id,session_id,task_id,0));
CREATE POLICY audit_insert ON retail_memory.audit_events FOR INSERT WITH CHECK (retail_memory.allowed(tenant_id,user_id,session_id,task_id,0));

CREATE FUNCTION retail_memory.guard_delete() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF OLD.legal_hold OR (OLD.retain_until IS NOT NULL AND OLD.retain_until > clock_timestamp()) THEN
        RAISE EXCEPTION 'Memory is protected by retention or legal hold' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER protect_retained_memory BEFORE DELETE ON retail_memory.memory_heads
    FOR EACH ROW EXECUTE FUNCTION retail_memory.guard_delete();
CREATE FUNCTION retail_memory.guard_revision() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.lifecycle <> 'SUPERSEDED' OR OLD.lifecycle = 'SUPERSEDED'
       OR (to_jsonb(NEW) - 'lifecycle') IS DISTINCT FROM (to_jsonb(OLD) - 'lifecycle') THEN
        RAISE EXCEPTION 'Historical revision bodies are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_revision BEFORE UPDATE ON retail_memory.memory_versions
    FOR EACH ROW EXECUTE FUNCTION retail_memory.guard_revision();

REVOKE ALL ON ALL TABLES IN SCHEMA retail_memory FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA retail_memory FROM PUBLIC;
GRANT USAGE ON SCHEMA retail_memory TO retail_memory_app,retail_memory_retention;
GRANT EXECUTE ON FUNCTION retail_memory.allowed(text,text,text,text,integer),retail_memory.can_change(text,text)
    TO retail_memory_app,retail_memory_retention;
GRANT SELECT,INSERT,DELETE ON retail_memory.memory_heads TO retail_memory_app;
GRANT UPDATE(current_version,verification,sensitivity,lifecycle,expires_at) ON retail_memory.memory_heads TO retail_memory_app;
GRANT SELECT,INSERT ON retail_memory.memory_versions TO retail_memory_app;
GRANT UPDATE(lifecycle) ON retail_memory.memory_versions TO retail_memory_app;
GRANT SELECT,INSERT ON retail_memory.audit_events TO retail_memory_app,retail_memory_retention;
GRANT SELECT ON retail_memory.memory_heads TO retail_memory_retention;
GRANT UPDATE(legal_hold,retain_until,retention_policy) ON retail_memory.memory_heads TO retail_memory_retention;
CREATE POLICY head_retention_update ON retail_memory.memory_heads FOR UPDATE TO retail_memory_retention
    USING (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity))
    WITH CHECK (retail_memory.allowed(tenant_id,user_id,session_id,task_id,sensitivity));
