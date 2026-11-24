# Docker preview — not a production deployment

```bash
cp deploy/.env.example deploy/.env.preview
# Set a strong unique password in deploy/.env.preview
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml up --build -d
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml ps
curl -f http://127.0.0.1:8080/actuator/health/liveness
curl -f http://127.0.0.1:8080/actuator/health/readiness
```

- PostgreSQL 16 preview container with volume; no external 5432 binding.
- Spring Boot 4.2.0 image, Java 21 non-root user, read-only root filesystem and readiness check.
- This **empty preview database** is not production and is not loaded with migrations 001–070.
- No reverse proxy or TLS included: port exposed to host loopback only.
- Portainer Stack Git deployments, registry authentication, release promotion, DB backups, and frontend integration will follow **after** parity/security checks.
- GitHub is the source repository. Gitea integration is postponed.
