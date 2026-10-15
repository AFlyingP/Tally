CREATE TABLE monitor.processed_event (
  event_id     uuid PRIMARY KEY,
  processed_at timestamptz(6) NOT NULL
);

CREATE TABLE monitor.movement (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id       uuid NOT NULL,
  transaction_id uuid NOT NULL,
  account_id     uuid NOT NULL,
  direction      text NOT NULL CHECK (direction IN ('IN','OUT')),
  kind           text NOT NULL,
  amount         bigint NOT NULL CHECK (amount > 0),
  currency       char(3) NOT NULL,
  counted        boolean NOT NULL,
  r2_candidate   boolean NOT NULL,
  event_time     timestamptz(6) NOT NULL,
  CONSTRAINT movement_uq UNIQUE (transaction_id, account_id)
);
CREATE INDEX movement_account_time_idx ON monitor.movement (account_id, event_time);
CREATE INDEX movement_r2_idx ON monitor.movement (account_id, event_time) WHERE r2_candidate;

CREATE TABLE monitor.case_file (
  id          uuid PRIMARY KEY,
  account_id  uuid NOT NULL,
  rule        text NOT NULL CHECK (rule IN ('R1','R2','R3')),
  status      text NOT NULL CHECK (status IN ('OPEN','IN_REVIEW','CLOSED_NO_ACTION','CLOSED_ESCALATED')),
  assignee    text,
  alert_count integer NOT NULL DEFAULT 0,
  opened_at   timestamptz(6) NOT NULL,
  updated_at  timestamptz(6) NOT NULL,
  closed_at   timestamptz(6)
);
CREATE INDEX case_file_lookup_idx ON monitor.case_file (account_id, rule, opened_at);
CREATE INDEX case_file_status_idx ON monitor.case_file (status, opened_at, id);

CREATE TABLE monitor.alert (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule        text NOT NULL CHECK (rule IN ('R1','R2','R3')),
  account_id  uuid NOT NULL,
  trigger_key text NOT NULL,
  alert_time  timestamptz(6) NOT NULL,
  detail      text NOT NULL,
  case_id     uuid REFERENCES monitor.case_file (id),
  created_at  timestamptz(6) NOT NULL,
  CONSTRAINT alert_uq UNIQUE (rule, account_id, trigger_key)
);
CREATE INDEX alert_case_idx ON monitor.alert (case_id, id);

CREATE TABLE monitor.case_event (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  case_id     uuid NOT NULL REFERENCES monitor.case_file (id),
  type        text NOT NULL CHECK (type IN ('OPENED','ALERT_ADDED','ASSIGNED','NOTE','CLOSED')),
  actor       text NOT NULL,
  detail      text,
  request_id  text,
  occurred_at timestamptz(6) NOT NULL
);
CREATE INDEX case_event_case_idx ON monitor.case_event (case_id, id);
CREATE FUNCTION monitor.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = 'TL001';
END $$;
CREATE TRIGGER case_event_no_update BEFORE UPDATE OR DELETE ON monitor.case_event
  FOR EACH ROW EXECUTE FUNCTION monitor.reject_mutation();
