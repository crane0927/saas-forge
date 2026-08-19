# Minimal Local Docker Compose

[简体中文](README.md)

This directory provides the minimum saas-forge local runtime topology for development, demonstrations, and end-to-end testing.

## Included components

- Gateway and the IAM, Tenant Access, Entitlement, and Audit domain services
- PostgreSQL 18 and one Flyway migration job per domain service
- Redis, single-node KRaft Kafka, single-node Nacos, and the OpenTelemetry Collector
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

On the first start, Nacos initializes with the explicit administrator password in `.env`; `nacos-init` then creates non-default IAM, Tenant Access, Entitlement, Audit, Gateway, and configuration-publisher development identities, the `dev` namespace, and their separate configuration resources in the `SAAS_FORGE` group. PostgreSQL then becomes healthy, the four `*-migrate` jobs migrate their own databases, the domain services start, and Gateway starts last. Compose explicitly passes `NACOS_TLS_ENABLED=false` to every application because it provides a single-node Nacos only on the isolated local network; never reuse this topology, address, or credential set in production. All five applications import only their own resource with `refreshEnabled=false`, so ordinary configuration changes use the controlled publishing process and a rolling deployment; no policy is dynamically refreshed locally. A service is not Ready when its Nacos configuration is absent, Nacos is unavailable, or registration fails; Gateway proxies every current public route only through healthy Nacos instances of its owning service, and Audit registration opens no new route. Access the local Nacos console at <http://127.0.0.1:8849/>. Inspect the status with:

```bash
docker compose ps --all
```

An `Exited (0)` status for a `*-migrate` job means its migration succeeded. The services do not yet expose business routes, so a `404` from a service root path is expected.

> [!IMPORTANT]
> `.env` is for local use only and is ignored by Git. Set one PostgreSQL administrator user and every required variable. Do not commit `.env` or use its local short codes outside local development.

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
| Nacos | 8848 / 8849 | Configuration and service-discovery API / local console; local development only |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP |

## Environment variables

`.env.example` lists the required variable names but provides no default passwords. `POSTGRES_ADMIN_USER` is the PostgreSQL bootstrap administrator; the remaining values are passwords or Nacos authentication material.

| Service | migrator password | app password |
| --- | --- | --- |
| PostgreSQL bootstrap | `POSTGRES_ADMIN_PASSWORD` | — |
| IAM | `IAM_MIGRATOR_PASSWORD` | `IAM_APP_PASSWORD` |
| Tenant Access | `TENANT_ACCESS_MIGRATOR_PASSWORD` | `TENANT_ACCESS_APP_PASSWORD` |
| Entitlement | `ENTITLEMENT_MIGRATOR_PASSWORD` | `ENTITLEMENT_APP_PASSWORD` |
| Audit | `AUDIT_MIGRATOR_PASSWORD` | `AUDIT_APP_PASSWORD` |
| Redis | `REDIS_PASSWORD` | — |
| Nacos | `NACOS_BOOTSTRAP_PASSWORD` | `NACOS_IAM_PASSWORD`, `NACOS_TENANT_ACCESS_PASSWORD`, `NACOS_ENTITLEMENT_PASSWORD`, `NACOS_AUDIT_PASSWORD`, `NACOS_GATEWAY_PASSWORD` |

`NACOS_IAM_USERNAME`, `NACOS_TENANT_ACCESS_USERNAME`, `NACOS_ENTITLEMENT_USERNAME`, `NACOS_AUDIT_USERNAME`, and `NACOS_GATEWAY_USERNAME` must be non-default development identities. Fill `NACOS_AUTH_IDENTITY_KEY`, `NACOS_AUTH_IDENTITY_VALUE`, and `NACOS_AUTH_TOKEN` with local-only random values; `NACOS_AUTH_TOKEN` must be a Base64 string generated from at least 32 raw characters. `nacos-init` uses the bootstrap administrator identity only to create the namespace, users, and permissions, then uses `NACOS_PUBLISH_USERNAME` to publish the manifest. Every domain-service identity may only read its own configuration and register its own stable service name, while the Gateway identity may only read its own configuration, register `gateway`, and read healthy `iam-service`, `tenant-access-service`, and `entitlement-service` instances. See [`../nacos/README.md`](../nacos/README.md) for the full manifest, CI publishing, and emergency reconciliation process.

On initial PostgreSQL volume creation, `bootstrap.sh` creates `iam_db`, `tenant_access_db`, `entitlement_db`, and `audit_db`, with separate `*_migrator` and `*_app` accounts for each service. Migration jobs use migrator accounts; runtime services use app accounts.

## Nacos failure-recovery acceptance

After preparing the local `.env`, run this from the repository root:

```bash
bash scripts/verify-nacos-failure-recovery.sh
```

The script uses a separate Compose project and `failure-recovery.override.yaml`; it neither claims the development stack's host ports nor stops its containers. It verifies that Gateway returns `503` with no healthy IAM instance and has no static-address fallback, that a running Gateway continues using known healthy instances during a brief Nacos outage, and that a new IAM instance cannot start without its required configuration. On exit it removes only containers and volumes created for the isolated acceptance project.

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
