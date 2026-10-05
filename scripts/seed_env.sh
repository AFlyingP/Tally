#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ] && [ "${1:-}" != "--force" ]; then
  echo ".env exists, not overwriting"
  exit 0
fi
command -v openssl >/dev/null 2>&1 || { echo "openssl not found" >&2; exit 1; }
count=0
: > .env.tmp
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    *=GENERATED)
      printf '%s=%s\n' "${line%%=*}" "$(openssl rand -hex 24)" >> .env.tmp
      count=$((count + 1))
      ;;
    *) printf '%s\n' "$line" >> .env.tmp ;;
  esac
done < .env.example
mv .env.tmp .env
echo "wrote .env with $count generated values"
