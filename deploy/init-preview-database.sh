#!/usr/bin/env bash
# PostgreSQL's official entrypoint only runs this for a BRAND-NEW data directory.
set -Eeuo pipefail
shopt -s nullglob

if [[ "${POSTGRES_DB:-}" != "memodb_preview" || "${POSTGRES_USER:-}" != "memo_preview" ]]; then
  echo "Refusing migration replay outside the isolated memo preview database" >&2
  exit 1
fi

migration_dir=/opt/memo-migrations
files=()
for number in $(seq 1 70); do
  version=$(printf '%03d' "$number")
  matches=( "$migration_dir"/"$version"_*.sql )
  if (( ${#matches[@]} != 1 )); then
    echo "Expected exactly one SQL migration for version $version, got ${#matches[@]}" >&2
    exit 1
  fi
  files+=(-f "${matches[0]}")
done

echo "Applying 70 pinned SQL migrations in one transaction to a fresh preview database"
psql -X --set ON_ERROR_STOP=1 --single-transaction --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" "${files[@]}"
version=$(psql -XAt --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c "select database_version from system.database_settings where singleton is true")
if [[ "$version" != "070" ]]; then
  echo "Unexpected schema version $version; expected 070" >&2
  exit 1
fi
echo "Preview SQL migration replay completed (schema 070)"
