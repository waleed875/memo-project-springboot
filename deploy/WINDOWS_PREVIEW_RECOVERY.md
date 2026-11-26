# Recover a Windows Docker Desktop preview after CRLF initialization failure

If PostgreSQL logs show `/usr/bin/env: 'bash\\r': No such file or directory`, a Windows CRLF checkout broke the SQL initialization hook. The PostgreSQL data volume now exists, so normal Docker restart **will not rerun** the script. The image now normalizes CRLF to LF during build, but it cannot automatically repair an already-created database.

These commands use **PowerShell** from the root of the `memo-project-springboot` clone. They retain the existing database volume.

1. Pull the repaired repository and rebuild *only* the tiny PostgreSQL image:

```powershell
git pull origin main
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml build postgres
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml up -d --no-deps postgres
```

2. Check that the database is the empty preview whose initialization failed (output should be an empty line for the schema check):

```powershell
docker exec memo-springboot-preview-postgres-1 psql -XAt -U memo_preview -d memodb_preview -c "select to_regclass('core.notes')"
```

**STOP if this shows `core.notes` or if you are not certain this is the disposable preview database.** Do not replay these scripts into a real or populated database.

3. Only if `core.notes` is absent and this is the failed *fresh preview*, apply the pinned migrations in the already-created volume:

```powershell
docker exec memo-springboot-preview-postgres-1 bash /docker-entrypoint-initdb.d/00-memo-preview.sh
docker exec memo-springboot-preview-postgres-1 psql -XAt -U memo_preview -d memodb_preview -c "select database_version from system.database_settings where singleton is true"
```

The second command must output `070`. The init script enforces the expected preview database and user and executes all 70 scripts in a single transaction, failing on the first SQL error.

4. Start the existing frontend and backend images (without rebuilding them):

```powershell
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up -d --no-build
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml ps
```

On success, the frontend is at http://127.0.0.1:8081. This preview has no usable default administrator password and the old frontend/API contract is not fully migrated.

**Never use `docker compose down -v` on a volume with data you need to preserve.** Replaying these migrations on a database that has partial migration content or real data requires separate review.
