# Minimal Local Docker Compose

[简体中文](README.md)

This directory provides the minimum saas-forge local runtime topology for development, demonstrations, and end-to-end testing.

## Included components

- Gateway and the IAM, Tenant Access, Entitlement, and Audit domain services
- PostgreSQL 18, Mailpit, and one Flyway migration job per domain service
- Redis, single-node KRaft Kafka, single-node Nacos, and the OpenTelemetry Collector
- Separate named volumes for PostgreSQL, Redis, and Kafka

S3-compatible object storage is outside this topology and will be introduced in phase 6. The Collector currently uses only the `debug` exporter; Prometheus, Loki, Tempo, and Grafana are not deployed.

## Start the stack

Run these commands from this directory:

```bash
test -f .env || cp .env.example .env
# Fill every variable in .env with local-development values.
bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose config
docker compose up --build
```

The initialization script creates a Git-ignored PKCS#8 RSA private key under `.secrets/`, runs the IAM Flyway migrations, and writes matching local metadata when the database has no ACTIVE Signing Key. It is safe to rerun; if the database already contains a different ACTIVE Key, it refuses to overwrite it and requires an explicit rotation.

On the first start, Nacos initializes with the explicit administrator password in `.env`; `nacos-init` then creates non-default IAM, Tenant Access, Entitlement, Audit, Gateway, and configuration-publisher development identities, the `dev` namespace, and their separate configuration resources in the `SAAS_FORGE` group. PostgreSQL then becomes healthy, the four `*-migrate` jobs migrate their own databases, the domain services start, and Gateway starts last. Compose explicitly passes `NACOS_TLS_ENABLED=false` to every application because it provides a single-node Nacos only on the isolated local network; never reuse this topology, address, or credential set in production. All five applications import only their own resource with `refreshEnabled=false`, so ordinary configuration changes use the controlled publishing process and a rolling deployment; no policy is dynamically refreshed locally. A service is not Ready when its Nacos configuration is absent, Nacos is unavailable, or registration fails; Gateway proxies every current public route only through healthy Nacos instances of its owning service, and Audit registration opens no new route. Access the local Nacos console at <http://127.0.0.1:8849/>. Inspect the status with:

```bash
docker compose ps --all
```

An `Exited (0)` status for a `*-migrate` job means its migration succeeded. The services do not yet expose business routes, so a `404` from a service root path is expected.

## Explicit Platform Admin bootstrap

Normal IAM startup never creates the Platform Admin. The account must be created by the explicit one-shot bootstrap task. Its random initial password is valid only for the first login and must be replaced within 24 hours of creation.

### 1. Configure the Secret file paths

`.env` contains only the external Secret file paths, never the email or password values:

```dotenv
IAM_PLATFORM_ADMIN_EMAIL_FILE=.secrets/platform-admin-email
IAM_PLATFORM_ADMIN_PASSWORD_FILE=.secrets/platform-admin-password
```

Create the email and random initial-password files from this directory:

```bash
mkdir -p .secrets
printf '%s\n' 'your-administrator-email' > .secrets/platform-admin-email
openssl rand -base64 32 > .secrets/platform-admin-password
chmod 600 .secrets/platform-admin-email .secrets/platform-admin-password
```

Both files must contain non-empty, single-line UTF-8 text and may have one trailing line ending. On macOS, copy the initial password to the clipboard without displaying it in the terminal:

```bash
pbcopy < .secrets/platform-admin-password
```

### 2. Rebuild and start IAM

The normal IAM service and the bootstrap task share `saasforge/iam-service:local`, so code changes require only one image build:

```bash
docker compose build iam-service
docker compose up -d iam-service gateway
```

If the entire stack has not been started yet, run:

```bash
docker compose up --build -d
```

### 3. Run the one-shot bootstrap

Run the bootstrap profile explicitly:

```bash
docker compose --profile bootstrap run --rm iam-platform-admin-bootstrap
```

The task waits for `iam-migrate` to succeed, then creates the Identity, 24-hour Initial Platform Credential, `PLATFORM_ADMIN` role, idempotency fact, and Outbox event in one IAM database transaction. An identical still-valid state can be replayed safely; any email, credential, or role drift fails without overwriting existing data. Secret files must contain single-line UTF-8 text and may have one trailing line ending. Logs contain only non-sensitive identifiers, expiry, outcome, and Trace ID. Normal `docker compose up` does not enable the `bootstrap` profile and neither mounts nor reads these Secrets.

If Docker reports `bind source path does not exist`, the host Secret files have not been created or their `.env` paths are incorrect. Check the files from this directory without printing their contents:

```bash
test -s .secrets/platform-admin-email &&
test -s .secrets/platform-admin-password &&
echo "Platform Admin Secret files are ready"
```

The bootstrap state intentionally changes after the initial password is replaced. Do not rerun the bootstrap task after a successful password change.

## Explicit reserved service OAuth Client bootstrap

Generate three deployment-local fixed Client IDs and Secrets from the Compose directory:

```bash
./generate-service-client-secrets.sh
```

The script uses `openssl` to generate UUIDv7 Client IDs and 256-bit random Secrets, applies `umask 077`, and refuses to overwrite existing files. Then run the explicit one-shot task:

```bash
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
```

Replay succeeds only when all three Client IDs, Secret digests, ACTIVE states, and fixed internal scopes match exactly. Drift fails without reconciliation. Normal startup does not execute this task, and each runtime service mounts only its own Client ID and Secret. No Secret value is stored in source, images, Compose values, or Nacos configuration.

### 4. Log in with the initial password

The following commands require `jq` and call the public endpoint through the local Gateway on port `8080`. Complete the initial login and password change in the same terminal because `COOKIE_JAR` holds the restricted session cookie:

```bash
API_BASE=http://localhost:8080
COOKIE_JAR="$(mktemp)"
ADMIN_EMAIL="$(<.secrets/platform-admin-email)"
INITIAL_PASSWORD="$(<.secrets/platform-admin-password)"

jq -n \
  --arg email "$ADMIN_EMAIL" \
  --arg password "$INITIAL_PASSWORD" \
  '{email:$email,password:$password,contextType:"PLATFORM"}' |
curl --fail-with-body -sS \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -H 'X-SF-CSRF: 1' \
  --data-binary @- \
  "$API_BASE/api/v1/auth/login" |
jq .
```

A successful initial-password login returns `PASSWORD_CHANGE_REQUIRED` and does not issue an Access Token:

```json
{
  "contextState": "PASSWORD_CHANGE_REQUIRED"
}
```

### 5. Replace the initial password

Enter the permanent password in the same terminal. No characters are echoed while typing; press Enter when finished:

```bash
read -r -s "NEW_PASSWORD?Enter the new password: "
echo

jq -n \
  --arg password "$NEW_PASSWORD" \
  '{newPassword:$password}' |
curl --fail-with-body -i \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -H 'X-SF-CSRF: 1' \
  --data-binary @- \
  "$API_BASE/api/v1/auth/password-changes"
```

`HTTP/1.1 204` means the permanent password is active and both the initial password and restricted session are permanently invalid. The permanent password must satisfy all of these rules:

- At least 12 Unicode code points;
- At most 128 Unicode code points and at most 512 UTF-8 bytes;
- No spaces, line endings, tabs, or other Unicode whitespace;
- Must not match the system's compromised-password blocklist.

After success, clear the shell variables and remove the initial-password file:

```bash
unset INITIAL_PASSWORD NEW_PASSWORD
rm .secrets/platform-admin-password
```

### 6. Log in again with the permanent password

Enter the permanent password again and call the login endpoint:

```bash
read -r -s "ADMIN_PASSWORD?Enter the permanent password: "
echo

jq -n \
  --arg email "$ADMIN_EMAIL" \
  --arg password "$ADMIN_PASSWORD" \
  '{email:$email,password:$password,contextType:"PLATFORM"}' |
curl --fail-with-body -sS \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -H 'X-SF-CSRF: 1' \
  --data-binary @- \
  "$API_BASE/api/v1/auth/login" |
jq .

unset ADMIN_PASSWORD
```

Success returns `ACCESS_TOKEN_ISSUED`, a Bearer Access Token, and its lifetime. Access Tokens, Refresh Token cookies, and passwords are sensitive and must never be written to `.env`, Git, logs, or chat messages. Remove the temporary cookie file when finished:

```bash
rm -f "$COOKIE_JAR"
unset COOKIE_JAR
```

## Restricted Platform Admin initial-credential reset

Only the Default Platform Admin that has not established a regular password can use this restricted reset task. Prepare a new UUIDv7 `resetRequestId` and a new random-password file for each new reset. Reuse the same `resetRequestId` only to replay the same operation:

```dotenv
IAM_PLATFORM_ADMIN_RESET_REQUEST_ID_FILE=.secrets/platform-admin-reset-request-id
IAM_PLATFORM_ADMIN_RESET_PASSWORD_FILE=.secrets/platform-admin-reset-password
```

```bash
docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d iam_db -Atc "SELECT uuidv7()"' \
  > .secrets/platform-admin-reset-request-id
openssl rand -base64 32 > .secrets/platform-admin-reset-password
chmod 600 \
  .secrets/platform-admin-reset-request-id \
  .secrets/platform-admin-reset-password
docker compose --profile credential-reset run --rm iam-platform-admin-credential-reset
```

The task starts no HTTP server and mounts only these two read-only Secrets. In one IAM database transaction it permanently invalidates all old initial credentials, revokes every `INITIAL_PASSWORD_CHANGE` family, and creates a new 24-hour initial credential, idempotency fact, and Outbox event. An active regular password, inconsistent Default Platform Admin state, or a non-canonical UUIDv7 request ID fails and rolls back the entire operation. Logs contain no password, hash, or Secret content. Delete the obsolete password file after success; a later reset requires both a new request ID and a new password.

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
| Mailpit | 1025 / 8025 | Development SMTP / mail web UI |
| Nacos | 8848 / 8849 | Configuration and service-discovery API / local console; local development only |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP |

## Environment variables

`.env.example` lists the required variable names but provides no default passwords. `POSTGRES_ADMIN_USER` is the PostgreSQL bootstrap administrator; the JWT issuer, Key Version reference, and local private-key path have development defaults within the local security boundary. The remaining values are passwords or Nacos authentication material.

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
