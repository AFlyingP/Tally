CREATE TABLE ledger.external_payment (
  id                        uuid PRIMARY KEY,
  source_account_id         uuid NOT NULL REFERENCES ledger.account (id),
  currency                  char(3) NOT NULL,
  amount                    bigint NOT NULL CHECK (amount > 0),
  creditor_account          text NOT NULL CHECK (creditor_account ~ '^[A-Z0-9-]{4,34}$'),
  external_reference        text NOT NULL UNIQUE CHECK (external_reference ~ '^[A-Z0-9-]{8,40}$'),
  state                     text NOT NULL CHECK (state IN ('INITIATED','HELD','CAPTURED','SUBMITTING',
                              'UNKNOWN','SETTLED','REJECTED_COMPENSATED','MANUAL_REVIEW','FAILED')),
  hold_id                   uuid REFERENCES ledger.hold (id),
  capture_transaction_id    uuid REFERENCES ledger.ledger_transaction (id),
  resolution_transaction_id uuid REFERENCES ledger.ledger_transaction (id),
  value_date                date,
  status_attempts           integer NOT NULL DEFAULT 0 CHECK (status_attempts BETWEEN 0 AND 8),
  next_attempt_at           timestamptz(6) NOT NULL,
  lease_owner               text,
  lease_until               timestamptz(6),
  reason                    text,
  idempotency_key_id        bigint NOT NULL UNIQUE REFERENCES ledger.idempotency_key (id),
  client_id                 text NOT NULL,
  request_id                text NOT NULL,
  created_at                timestamptz(6) NOT NULL,
  updated_at                timestamptz(6) NOT NULL,
  CONSTRAINT payment_settled_shape CHECK (state <> 'SETTLED' OR (value_date IS NOT NULL AND resolution_transaction_id IS NOT NULL)),
  CONSTRAINT payment_compensated_shape CHECK (state <> 'REJECTED_COMPENSATED' OR resolution_transaction_id IS NOT NULL)
);
CREATE INDEX payment_due_idx ON ledger.external_payment (next_attempt_at, id)
  WHERE state IN ('INITIATED','HELD','CAPTURED','SUBMITTING','UNKNOWN');
CREATE INDEX payment_value_date_idx ON ledger.external_payment (value_date) WHERE state = 'SETTLED';
CREATE INDEX payment_source_idx ON ledger.external_payment (source_account_id);
GRANT SELECT, INSERT, UPDATE ON ledger.external_payment TO ledger_app;

CREATE TABLE ledger.payment_event (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  payment_id  uuid NOT NULL REFERENCES ledger.external_payment (id),
  from_state  text,
  to_state    text NOT NULL,
  detail      text,
  occurred_at timestamptz(6) NOT NULL,
  audit_id    uuid NOT NULL UNIQUE REFERENCES ledger.audit_log (id)
);
CREATE INDEX payment_event_payment_idx ON ledger.payment_event (payment_id, id);
CREATE TRIGGER payment_event_no_update BEFORE UPDATE OR DELETE ON ledger.payment_event
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER payment_event_no_truncate BEFORE TRUNCATE ON ledger.payment_event
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
GRANT SELECT, INSERT ON ledger.payment_event TO ledger_app;
GRANT USAGE ON SEQUENCE ledger.payment_event_id_seq TO ledger_app;
