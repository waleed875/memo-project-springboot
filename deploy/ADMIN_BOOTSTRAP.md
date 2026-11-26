# Initialize the first administrator in a disposable PostgreSQL preview

The original SQL migration 004 creates username `admin` with the **non-password**
`BOOTSTRAP_BCRYPT_ADMIN123`. Regular login intentionally rejects this placeholder.

This feature provides a one-time, **local CLI only** account activation. It exposes no HTTP bootstrap route and does not use shared/default credentials.

## Requirements

- You must be running the isolated `memo-springboot-preview` PostgreSQL service.
- The database name and role must be `memodb_preview` / `memo_preview`.
- All pinned migrations 001–070 must already have run.
- There must be exactly one user (the original untouched `admin` account) and **zero notes**.
- A previously initialized admin, another user, another database, or any existing notes will cause refusal.
- The database volume and `POSTGRES_PASSWORD` must remain unchanged.

## Windows PowerShell

Run from the clone root:

```powershell
git pull origin main
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml build memo-api
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up -d --no-build
powershell -NoProfile -File .\deploy\bootstrap-preview-admin.ps1
```

The PowerShell script supports **Windows PowerShell 5.1** (the built-in `powershell.exe`) as well as PowerShell 7; it writes raw UTF-8 bytes through Docker's redirected stdin rather than using the unavailable .NET Framework `ProcessStartInfo.StandardInputEncoding` property. It prompts twice with hidden input; enter a **new unique 12–72 character** password (up to 72 UTF-8 bytes). It sends the password through stdin to a one-off Spring CLI container, never as a docker argument, environment variable, or file. The input is briefly converted to a managed .NET string in memory and cannot be guaranteed to be fully erased there. Do not run this on a shared/untrusted machine. The database stores only a BCrypt hash.

After the command succeeds, open the configured frontend port (default 8081; 8082 if `MEMO_FRONTEND_PORT=8082`) and log in with:

- Username: `admin`
- Password: the unique password you just entered

The password is **not** your `POSTGRES_PASSWORD`; these are entirely separate secrets.

## Security model

The server refuses activation unless database name, DB user, schema version,
account count, note count and original placeholder hash match. The actual
update includes all these predicates again and runs inside a transaction
which records an `ADMIN_INITIALIZED` audit event. A second attempt cannot
overwrite the BCrypt hash. The account's `updated_at` is advanced to invalidate
any preexisting sessions. No HTTP endpoint provides account activation.

This is a **preview-only first-admin procedure**, not a password-reset tool.
Never use it against production or an existing/partially configured database.
It does not make the unfinished legacy routes production-ready.

If the command fails, **do not** reset the PostgreSQL volume with `down -v`.
Investigate before attempting any database mutation.
