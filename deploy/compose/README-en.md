# Minimal Local Docker Compose

[简体中文](README.md)

This directory provides the minimum saas-forge local runtime topology for development, demonstrations, and end-to-end testing.

## Included components

- Gateway and the IAM, Tenant Access, Entitlement, and Audit domain services
- PostgreSQL 18 and one Flyway migration job per domain service
- Redis, single-node KRaft Kafka, and the OpenTelemetry Collector
- Separate named volumes for PostgreSQL, Redis, and Kafka

S3-compatible object storage is outside this topology and will be introduced in phase 6. The Collector currently uses only the `debug` exporter; Prometheus, Loki, Tempo, and Grafana are not deployed.

## Start the stack

Run these commands from this directory:

```bash
test -f .env || cp .env.example .env
# Fill every variable in .env with local-development values.
docker compose config
docker compose up --build
```

On the first start, PostgreSQL becomes healthy, the four `*-migrate` jobs migrate their own databases, the domain services start, and Gateway starts last. Inspect the status with:

```bash
docker compose ps --all
```

An `Exited (0)` status for a `*-migrate` job means its migration succeeded. The services do not yet expose business routes, so a `404` from a service root path is expected.

> [!IMPORTANT]
> `.env` is for local use only and is ignored by Git. Set one PostgreSQL administrator user and all ten password variables. Do not commit `.env` or use its local short codes outside local development.

## Local ports

Every host port binds only to `127.0.0.1`; none is exposed to the local network.

| Component | Local port | Notes |
| --- | ---: | --- |
| Gateway | 8080 | HTTP |
| IAM | 8081 | HTTP |
| Tenant Access | 8082 | HTTP |
| Entitlement | 8083 | HTTP |
| Audit | 8084 | HTTP |
| PostgreSQL | 5432 | Database connection |
| Redis | 6379 | Authenticate with `REDIS_PASSWORD` |
| Kafka | 29092 | Host external listener; containers use `kafka:9092` |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP |

## Environment variables

`.env.example` lists the required variable names but provides no default passwords. `POSTGRES_ADMIN_USER` is the PostgreSQL bootstrap administrator; every other variable below is a password.

| Service | migrator password | app password |
| --- | --- | --- |
| PostgreSQL bootstrap | `POSTGRES_ADMIN_PASSWORD` | — |
| IAM | `IAM_MIGRATOR_PASSWORD` | `IAM_APP_PASSWORD` |
| Tenant Access | `TENANT_ACCESS_MIGRATOR_PASSWORD` | `TENANT_ACCESS_APP_PASSWORD` |
| Entitlement | `ENTITLEMENT_MIGRATOR_PASSWORD` | `ENTITLEMENT_APP_PASSWORD` |
| Audit | `AUDIT_MIGRATOR_PASSWORD` | `AUDIT_APP_PASSWORD` |
| Redis | `REDIS_PASSWORD` | — |

On initial PostgreSQL volume creation, `bootstrap.sh` creates `iam_db`, `tenant_access_db`, `entitlement_db`, and `audit_db`, with separate `*_migrator` and `*_app` accounts for each service. Migration jobs use migrator accounts; runtime services use app accounts.

## Stop and reset

To stop the stack normally:

```bash
docker compose down
```

To reset local PostgreSQL, Redis, and Kafka data completely:

```bash
docker compose down -v
```

> [!CAUTION]
> `down -v` deletes the three named data volumes of this Compose project. Use it only after confirming that no local data must be retained.
