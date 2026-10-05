#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE ledger_owner LOGIN PASSWORD '${LEDGER_OWNER_PASSWORD}';
CREATE ROLE ledger_app LOGIN PASSWORD '${LEDGER_APP_PASSWORD}';
CREATE ROLE monitor_app LOGIN PASSWORD '${MONITOR_DB_PASSWORD}';
CREATE ROLE auth_app LOGIN PASSWORD '${AUTH_DB_PASSWORD}';
CREATE ROLE extbank_app LOGIN PASSWORD '${EXTBANK_DB_PASSWORD}';
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA ledger AUTHORIZATION ledger_owner;
GRANT USAGE ON SCHEMA ledger TO ledger_app;
CREATE SCHEMA monitor AUTHORIZATION monitor_app;
CREATE SCHEMA auth AUTHORIZATION auth_app;
CREATE SCHEMA extbank AUTHORIZATION extbank_app;
ALTER ROLE ledger_owner SET search_path = ledger;
ALTER ROLE ledger_app SET search_path = ledger;
ALTER ROLE monitor_app SET search_path = monitor;
ALTER ROLE auth_app SET search_path = auth;
ALTER ROLE extbank_app SET search_path = extbank;
SQL
