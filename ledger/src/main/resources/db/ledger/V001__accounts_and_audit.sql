CREATE FUNCTION ledger.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = 'TL001';
END $$;

CREATE TABLE ledger.account (
  id          uuid PRIMARY KEY,
  kind        text NOT NULL CHECK (kind IN ('CUSTOMER','SETTLEMENT','CLEARING')),
  currency    char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  normal_side text NOT NULL CHECK (normal_side IN ('DEBIT','CREDIT')),
  min_balance bigint,
  status      text NOT NULL CHECK (status IN ('OPEN','FROZEN','CLOSED')),
  balance     bigint NOT NULL DEFAULT 0,
  held        bigint NOT NULL DEFAULT 0 CHECK (held >= 0),
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz(6) NOT NULL,
  updated_at  timestamptz(6) NOT NULL,
  CONSTRAINT account_id_currency_uq UNIQUE (id, currency),
  CONSTRAINT account_limit_presence CHECK ((kind = 'CUSTOMER') = (min_balance IS NOT NULL)),
  CONSTRAINT account_limit_range CHECK (min_balance IS NULL OR min_balance BETWEEN -4000000000000000000 AND 0),
  CONSTRAINT account_above_limit CHECK (kind <> 'CUSTOMER' OR balance - held >= min_balance)
);
CREATE UNIQUE INDEX account_internal_uq ON ledger.account (kind, currency) WHERE kind <> 'CUSTOMER';
GRANT SELECT, INSERT, UPDATE ON ledger.account TO ledger_app;

CREATE TABLE ledger.audit_log (
  id             uuid PRIMARY KEY,
  occurred_at    timestamptz(6) NOT NULL,
  actor          text NOT NULL,
  actor_role     text,
  scopes         text NOT NULL,
  action         text NOT NULL,
  resource_type  text NOT NULL,
  resource_id    text NOT NULL,
  request_id     text NOT NULL,
  outcome        text NOT NULL CHECK (outcome IN ('SUCCESS','DENIED')),
  reason         text,
  transaction_id uuid,
  row_hash       bytea NOT NULL CHECK (octet_length(row_hash) = 32)
);
CREATE UNIQUE INDEX audit_log_transaction_uq ON ledger.audit_log (transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX audit_log_resource_idx ON ledger.audit_log (resource_type, resource_id, occurred_at, id);
CREATE INDEX audit_log_time_idx ON ledger.audit_log (occurred_at, id);
CREATE INDEX audit_log_request_idx ON ledger.audit_log (request_id);
CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON ledger.audit_log
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON ledger.audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
GRANT SELECT, INSERT ON ledger.audit_log TO ledger_app;
