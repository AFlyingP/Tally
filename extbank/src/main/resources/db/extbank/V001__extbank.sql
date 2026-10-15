CREATE TABLE extbank.payment (
  reference        text PRIMARY KEY CHECK (reference ~ '^[A-Z0-9-]{8,40}$'),
  creditor_account text NOT NULL,
  amount           bigint NOT NULL CHECK (amount > 0),
  currency         char(3) NOT NULL,
  status           text NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REJECTED')),
  reject_reason    text,
  value_date       date,
  confirmations    integer NOT NULL DEFAULT 0 CHECK (confirmations BETWEEN 0 AND 2),
  received_at      timestamptz(6) NOT NULL,
  CONSTRAINT payment_confirmed_shape CHECK ((status = 'CONFIRMED') = (value_date IS NOT NULL AND confirmations >= 1))
);
CREATE INDEX payment_value_date_idx ON extbank.payment (value_date, reference) WHERE status = 'CONFIRMED';

CREATE TABLE extbank.rule (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  creditor_account text,
  behaviour        text NOT NULL CHECK (behaviour IN ('ACCEPT','REJECT','DELAY','DROP_RESPONSE','CONFIRM_TWICE','SERVER_ERROR')),
  delay_ms         integer NOT NULL DEFAULT 0 CHECK (delay_ms BETWEEN 0 AND 60000),
  remaining        integer CHECK (remaining >= 0),
  created_at       timestamptz(6) NOT NULL
);
