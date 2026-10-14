CREATE TABLE ledger.schedule (
  id                     uuid PRIMARY KEY,
  source_account_id      uuid NOT NULL REFERENCES ledger.account (id),
  destination_account_id uuid NOT NULL REFERENCES ledger.account (id),
  currency               char(3) NOT NULL,
  amount                 bigint NOT NULL CHECK (amount > 0),
  frequency              text NOT NULL CHECK (frequency IN ('ONCE','DAILY','WEEKLY','MONTHLY')),
  start_at               timestamptz(6) NOT NULL,
  day_of_month           smallint CHECK (day_of_month BETWEEN 1 AND 31),
  end_date               date,
  status                 text NOT NULL CHECK (status IN ('ACTIVE','CANCELLED','COMPLETED')),
  description            text CHECK (char_length(description) <= 200),
  idempotency_key_id     bigint NOT NULL UNIQUE REFERENCES ledger.idempotency_key (id),
  client_id              text NOT NULL,
  created_at             timestamptz(6) NOT NULL,
  updated_at             timestamptz(6) NOT NULL,
  CONSTRAINT schedule_monthly_shape CHECK ((frequency = 'MONTHLY') = (day_of_month IS NOT NULL)),
  CONSTRAINT schedule_once_shape CHECK (frequency <> 'ONCE' OR end_date IS NULL),
  CONSTRAINT schedule_distinct_accounts CHECK (source_account_id <> destination_account_id)
);
GRANT SELECT, INSERT, UPDATE ON ledger.schedule TO ledger_app;

CREATE TABLE ledger.schedule_occurrence (
  id              uuid PRIMARY KEY,
  schedule_id     uuid NOT NULL REFERENCES ledger.schedule (id),
  occurrence_date date NOT NULL,
  due_at          timestamptz(6) NOT NULL,
  state           text NOT NULL CHECK (state IN ('PENDING','POSTED','FAILED','SKIPPED')),
  attempts        integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4),
  next_attempt_at timestamptz(6) NOT NULL,
  transaction_id  uuid UNIQUE REFERENCES ledger.ledger_transaction (id),
  reason          text,
  updated_at      timestamptz(6) NOT NULL,
  CONSTRAINT occurrence_uq UNIQUE (schedule_id, occurrence_date),
  CONSTRAINT occurrence_posted_shape CHECK ((state = 'POSTED') = (transaction_id IS NOT NULL))
);
CREATE INDEX occurrence_due_idx ON ledger.schedule_occurrence (next_attempt_at, id) WHERE state = 'PENDING';
GRANT SELECT, INSERT, UPDATE ON ledger.schedule_occurrence TO ledger_app;
