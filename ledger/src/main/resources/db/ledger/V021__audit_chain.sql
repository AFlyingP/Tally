CREATE TABLE ledger.audit_pending (
  audit_id uuid PRIMARY KEY REFERENCES ledger.audit_log (id)
);
GRANT SELECT, INSERT, DELETE ON ledger.audit_pending TO ledger_app;

CREATE FUNCTION ledger.enqueue_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO ledger.audit_pending (audit_id) VALUES (NEW.id);
  RETURN NULL;
END $$;
CREATE TRIGGER audit_log_enqueue AFTER INSERT ON ledger.audit_log
  FOR EACH ROW EXECUTE FUNCTION ledger.enqueue_audit();
INSERT INTO ledger.audit_pending (audit_id) SELECT id FROM ledger.audit_log;

CREATE TABLE ledger.audit_chain (
  seq        bigint PRIMARY KEY,
  audit_id   uuid NOT NULL UNIQUE REFERENCES ledger.audit_log (id),
  prev_hash  bytea NOT NULL CHECK (octet_length(prev_hash) = 32),
  chain_hash bytea NOT NULL CHECK (octet_length(chain_hash) = 32),
  sealed_at  timestamptz(6) NOT NULL
);
CREATE TRIGGER audit_chain_no_update BEFORE UPDATE OR DELETE ON ledger.audit_chain
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER audit_chain_no_truncate BEFORE TRUNCATE ON ledger.audit_chain
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
GRANT SELECT, INSERT ON ledger.audit_chain TO ledger_app;
