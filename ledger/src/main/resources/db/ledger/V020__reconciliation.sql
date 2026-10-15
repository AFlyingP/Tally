CREATE TABLE ledger.recon_run (
  id              uuid PRIMARY KEY,
  business_date   date NOT NULL,
  file_sha256     bytea NOT NULL CHECK (octet_length(file_sha256) = 32),
  status          text NOT NULL CHECK (status IN ('COMPLETED','FAILED')),
  failure_line    integer,
  failure_reason  text,
  previous_run_id uuid REFERENCES ledger.recon_run (id),
  statement_lines integer NOT NULL DEFAULT 0,
  ledger_payments integer NOT NULL DEFAULT 0,
  matched         integer NOT NULL DEFAULT 0,
  break_count     integer NOT NULL DEFAULT 0,
  started_at      timestamptz(6) NOT NULL,
  finished_at     timestamptz(6) NOT NULL,
  CONSTRAINT recon_run_file_uq UNIQUE (business_date, file_sha256),
  CONSTRAINT recon_run_failed_shape CHECK ((status = 'FAILED') = (failure_line IS NOT NULL AND failure_reason IS NOT NULL))
);
CREATE INDEX recon_run_date_idx ON ledger.recon_run (business_date, started_at);
GRANT SELECT, INSERT ON ledger.recon_run TO ledger_app;

CREATE TABLE ledger.recon_break (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id             uuid NOT NULL REFERENCES ledger.recon_run (id),
  type               text NOT NULL CHECK (type IN ('MISSING_IN_LEDGER','MISSING_IN_STATEMENT','AMOUNT_MISMATCH',
                       'CURRENCY_MISMATCH','DATE_MISMATCH','DUPLICATE_IN_STATEMENT')),
  reference          text NOT NULL,
  line_number        integer,
  ledger_amount      bigint,
  ledger_currency    char(3),
  ledger_date        date,
  statement_amount   bigint,
  statement_currency char(3),
  statement_date     date,
  CONSTRAINT recon_break_uq UNIQUE (run_id, reference)
);
GRANT SELECT, INSERT ON ledger.recon_break TO ledger_app;
GRANT USAGE ON SEQUENCE ledger.recon_break_id_seq TO ledger_app;
