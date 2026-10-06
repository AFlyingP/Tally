CREATE TABLE ledger.idempotency_key (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_id     text NOT NULL,
  key           text NOT NULL CHECK (key ~ '^[A-Za-z0-9_.:-]{1,128}$'),
  request_hash  bytea NOT NULL CHECK (octet_length(request_hash) = 32),
  status_code   smallint NOT NULL,
  response_body text NOT NULL,
  created_at    timestamptz(6) NOT NULL,
  CONSTRAINT idempotency_key_uq UNIQUE (client_id, key)
);
GRANT SELECT, INSERT, UPDATE ON ledger.idempotency_key TO ledger_app;
GRANT USAGE ON SEQUENCE ledger.idempotency_key_id_seq TO ledger_app;

CREATE TABLE ledger.ledger_transaction (
  id                 uuid PRIMARY KEY,
  kind               text NOT NULL CHECK (kind IN ('DEPOSIT','WITHDRAWAL','TRANSFER','REVERSAL',
                       'HOLD_CAPTURE','EXTERNAL_SETTLEMENT','EXTERNAL_COMPENSATION')),
  currency           char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  amount             bigint NOT NULL CHECK (amount > 0),
  idempotency_key_id bigint NOT NULL REFERENCES ledger.idempotency_key (id),
  reverses_id        uuid REFERENCES ledger.ledger_transaction (id),
  description        text CHECK (char_length(description) <= 200),
  client_id          text NOT NULL,
  request_id         text NOT NULL,
  posted_at          timestamptz(6) NOT NULL,
  CONSTRAINT ledger_transaction_key_uq UNIQUE (idempotency_key_id),
  CONSTRAINT ledger_transaction_reverses_uq UNIQUE (reverses_id),
  CONSTRAINT ledger_transaction_reversal_shape CHECK ((kind = 'REVERSAL') = (reverses_id IS NOT NULL))
);
GRANT SELECT, INSERT ON ledger.ledger_transaction TO ledger_app;

CREATE TABLE ledger.entry (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id uuid NOT NULL REFERENCES ledger.ledger_transaction (id),
  account_id     uuid NOT NULL,
  currency       char(3) NOT NULL,
  side           text NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
  amount         bigint NOT NULL CHECK (amount > 0),
  posted_at      timestamptz(6) NOT NULL,
  CONSTRAINT entry_account_fk FOREIGN KEY (account_id, currency) REFERENCES ledger.account (id, currency)
);
CREATE INDEX entry_account_idx ON ledger.entry (account_id, id);
CREATE INDEX entry_account_time_idx ON ledger.entry (account_id, posted_at);
CREATE INDEX entry_transaction_idx ON ledger.entry (transaction_id);
GRANT SELECT, INSERT ON ledger.entry TO ledger_app;
GRANT USAGE ON SEQUENCE ledger.entry_id_seq TO ledger_app;

CREATE TABLE ledger.outbox (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id       uuid NOT NULL UNIQUE,
  transaction_id uuid NOT NULL UNIQUE REFERENCES ledger.ledger_transaction (id),
  key_account_id uuid NOT NULL,
  request_id     text NOT NULL,
  payload        text NOT NULL,
  created_at     timestamptz(6) NOT NULL,
  published_at   timestamptz(6)
);
CREATE INDEX outbox_unpublished_idx ON ledger.outbox (id) WHERE published_at IS NULL;
GRANT SELECT, INSERT ON ledger.outbox TO ledger_app;
GRANT UPDATE (published_at) ON ledger.outbox TO ledger_app;
GRANT USAGE ON SEQUENCE ledger.outbox_id_seq TO ledger_app;

CREATE FUNCTION ledger.check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  debits numeric; credits numeric; lines int; foreign_currency int;
BEGIN
  SELECT coalesce(sum(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0),
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0),
         count(*),
         count(*) FILTER (WHERE e.currency <> NEW.currency)
    INTO debits, credits, lines, foreign_currency
    FROM ledger.entry e WHERE e.transaction_id = NEW.id;
  IF lines < 2 OR debits <> credits OR foreign_currency > 0 OR debits <> NEW.amount THEN
    RAISE EXCEPTION 'transaction % is not balanced: lines=%, debits=%, credits=%',
      NEW.id, lines, debits, credits USING ERRCODE = 'TL002';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_transaction_balanced AFTER INSERT ON ledger.ledger_transaction
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_balanced();

CREATE FUNCTION ledger.check_entry_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM ledger.ledger_transaction t
                  WHERE t.id = NEW.transaction_id AND t.xmin = pg_current_xact_id()::xid) THEN
    RAISE EXCEPTION 'entry for transaction % added outside its creating database transaction',
      NEW.transaction_id USING ERRCODE = 'TL003';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER entry_parent BEFORE INSERT ON ledger.entry
  FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_parent();

CREATE TRIGGER ledger_transaction_no_update BEFORE UPDATE OR DELETE ON ledger.ledger_transaction
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER ledger_transaction_no_truncate BEFORE TRUNCATE ON ledger.ledger_transaction
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER entry_no_update BEFORE UPDATE OR DELETE ON ledger.entry
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON ledger.entry
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
