CREATE TABLE auth.api_client (
  client_id   text PRIMARY KEY CHECK (client_id ~ '^[a-z0-9-]{3,64}$'),
  secret_hash text NOT NULL,
  role        text NOT NULL CHECK (role IN ('service-desk','operator','admin','auditor','compliance')),
  created_at  timestamptz(6) NOT NULL,
  disabled_at timestamptz(6)
);

CREATE TABLE auth.client_event (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_id   text NOT NULL,
  type        text NOT NULL CHECK (type IN ('CREATED','DISABLED','BOOTSTRAPPED')),
  actor       text NOT NULL,
  request_id  text,
  occurred_at timestamptz(6) NOT NULL
);
CREATE FUNCTION auth.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = 'TL001';
END $$;
CREATE TRIGGER client_event_no_update BEFORE UPDATE OR DELETE ON auth.client_event
  FOR EACH ROW EXECUTE FUNCTION auth.reject_mutation();
