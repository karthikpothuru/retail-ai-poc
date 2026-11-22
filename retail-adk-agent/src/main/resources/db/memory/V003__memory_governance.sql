-- Additive governance audit. Existing memory and vector policies remain unchanged.
CREATE TABLE retail_memory.governance_events (
 event_id uuid PRIMARY KEY, occurred_at timestamptz NOT NULL,
 tenant_id text, user_id text, authentication_id uuid,
 operation text NOT NULL CHECK(operation ~ '^[A-Z_]{1,40}$'),
 outcome text NOT NULL CHECK(outcome IN ('SUCCESS','DENIED','ROLLED_BACK')),
 reason text NOT NULL CHECK(reason ~ '^[A-Z_]{1,60}$'),
 memory_id uuid, memory_version bigint,
 CHECK ((tenant_id IS NULL) = (user_id IS NULL)),
 CHECK (outcome='SUCCESS' OR (memory_id IS NULL AND memory_version IS NULL))
);
CREATE INDEX governance_event_scope ON retail_memory.governance_events(tenant_id,user_id,occurred_at);
ALTER TABLE retail_memory.governance_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE retail_memory.governance_events FORCE ROW LEVEL SECURITY;
GRANT USAGE ON SCHEMA retail_memory TO retail_memory_audit_writer;
GRANT INSERT ON retail_memory.governance_events TO retail_memory_app,retail_memory_audit_writer;
CREATE POLICY governance_success ON retail_memory.governance_events FOR INSERT TO retail_memory_app
 WITH CHECK(outcome='SUCCESS' AND tenant_id=nullif(current_setting('retail.tenant_id',true),'')
 AND user_id=nullif(current_setting('retail.user_id',true),'') AND authentication_id IS NOT NULL);
CREATE POLICY governance_denial ON retail_memory.governance_events FOR INSERT TO retail_memory_audit_writer
 WITH CHECK(outcome IN ('DENIED','ROLLED_BACK') AND memory_id IS NULL AND memory_version IS NULL);
-- No runtime SELECT/UPDATE/DELETE; audit administration requires separately controlled DBA access.
