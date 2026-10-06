ALTER TABLE ledger.ledger_transaction
  ADD COLUMN created_xid xid8 NOT NULL DEFAULT pg_current_xact_id();

CREATE OR REPLACE FUNCTION ledger.check_entry_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM ledger.ledger_transaction t
                  WHERE t.id = NEW.transaction_id AND t.created_xid = pg_current_xact_id()) THEN
    RAISE EXCEPTION 'entry for transaction % added outside its creating database transaction',
      NEW.transaction_id USING ERRCODE = 'TL003';
  END IF;
  RETURN NEW;
END $$;
