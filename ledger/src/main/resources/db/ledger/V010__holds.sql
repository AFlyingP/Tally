CREATE TABLE ledger.hold (
  id                     uuid PRIMARY KEY,
  account_id             uuid NOT NULL REFERENCES ledger.account (id),
  currency               char(3) NOT NULL,
  amount                 bigint NOT NULL CHECK (amount > 0),
  state                  text NOT NULL CHECK (state IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
  expires_at             timestamptz(6) NOT NULL,
  captured_amount        bigint,
  capture_transaction_id uuid UNIQUE REFERENCES ledger.ledger_transaction (id),
  idempotency_key_id     bigint NOT NULL UNIQUE REFERENCES ledger.idempotency_key (id),
  client_id              text NOT NULL,
  created_at             timestamptz(6) NOT NULL,
  updated_at             timestamptz(6) NOT NULL,
  CONSTRAINT hold_capture_shape CHECK ((state = 'CAPTURED') = (captured_amount IS NOT NULL AND capture_transaction_id IS NOT NULL)),
  CONSTRAINT hold_capture_range CHECK (captured_amount IS NULL OR captured_amount BETWEEN 1 AND amount)
);
CREATE INDEX hold_due_idx ON ledger.hold (expires_at, id) WHERE state = 'ACTIVE';
CREATE INDEX hold_account_idx ON ledger.hold (account_id) WHERE state = 'ACTIVE';
GRANT SELECT, INSERT, UPDATE ON ledger.hold TO ledger_app;

CREATE TABLE ledger.hold_event (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  hold_id        uuid NOT NULL REFERENCES ledger.hold (id),
  type           text NOT NULL CHECK (type IN ('PLACED','CAPTURED','RELEASED','EXPIRED')),
  amount         bigint NOT NULL CHECK (amount >= 0),
  transaction_id uuid REFERENCES ledger.ledger_transaction (id),
  actor          text NOT NULL,
  occurred_at    timestamptz(6) NOT NULL,
  audit_id       uuid NOT NULL UNIQUE REFERENCES ledger.audit_log (id)
);
CREATE UNIQUE INDEX hold_event_placed_uq ON ledger.hold_event (hold_id) WHERE type = 'PLACED';
CREATE UNIQUE INDEX hold_event_final_uq ON ledger.hold_event (hold_id) WHERE type <> 'PLACED';
CREATE TRIGGER hold_event_no_update BEFORE UPDATE OR DELETE ON ledger.hold_event
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER hold_event_no_truncate BEFORE TRUNCATE ON ledger.hold_event
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
GRANT SELECT, INSERT ON ledger.hold_event TO ledger_app;
GRANT USAGE ON SEQUENCE ledger.hold_event_id_seq TO ledger_app;
