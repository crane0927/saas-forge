# Minimal Local Docker Compose

[简体中文](README.md)

This directory provides the minimum saas-forge local runtime topology for development, demonstrations, and end-to-end testing. The default `compose.yaml` starts only the backend and infrastructure; it does not include either Console or a browser HTTPS entry point.

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

An `Exited (0)` status for a `*-migrate` job means its migration succeeded. The backend already exposes authentication and management APIs. Service roots have no page; a root `404` neither means the APIs are unavailable nor proves service readiness.

## Operations available in the UI

These operations require deployed frontends, HTTPS entry points, healthy backend services, and the appropriate account data. Running `docker compose up` alone does not make the product consoles available.

| Operation | Current entry point | Prerequisite or boundary |
| --- | --- | --- |
| Platform Admin login, initial password change, subsequent login, and logout | Platform Console | Run the administrator bootstrap below first; use the initial password within 24 hours |
| Session recovery after reload and coordination across tabs | Platform / Tenant Console | An established session; platform and tenant sessions are managed separately |
| Tenant login, selection, switching, and logout | Tenant Console | Prepare accessible Tenants and Memberships through backend APIs first; selection or switching requires multiple accessible Memberships |
| Set a Tenant administrator's first password | Password Setup link in a Mailpit email | Administrator initialization must have sent a still-valid link; return to Tenant Console to log in afterward |
| Create the Platform Admin or reset its initial credential | Compose one-shot tasks below | No platform UI; the restricted reset cannot reset an established regular password |
| Bootstrap reserved service OAuth Clients or replace revoked Clients | Compose one-shot tasks below | No platform UI |
| Manage OAuth Clients, create Tenants, configure Quota/Plan or Subscription, initialize Tenant administrators | Formal backend APIs | No working management pages yet; platform `/oauth-clients` is only a placeholder |

The platform home and Tenant workspace currently show authentication status only, without a statistics Dashboard or business management actions. See the [Tenant lifecycle acceptance script](../../scripts/verify-tenant-lifecycle-e2e.sh) for API examples; API coverage in that script does not mean corresponding UI features exist.

### Browser access prerequisites

1. Build and separately host `consoles/platform-console/dist` and `consoles/tenant-console-shell/dist`, following the [Console README](../../consoles/README.md). The default Compose stack does not do this.
2. Resolve `platform.saasforge.test`, `console.saasforge.test`, and `api.saasforge.test` to `127.0.0.1`, with browser-trusted TLS on HTTPS port 443. Route the first two hosts to their frontends and proxy the API host to Gateway. Different HTTP localhost ports cannot replace these entry points.
3. Replace `/runtime-config.json` in both deployed frontends with the following content. The original build artifact contains an intentionally invalid template; leaving it unchanged keeps the application on the configuration-error screen.

   ```json
   {
     "schemaVersion": 1,
     "apiBaseUrl": "https://api.saasforge.test"
   }
   ```

4. For password setup, proxy `/password-setup`, `/password-setup/app.js`, `/password-setup/styles.css`, and the submission path `/api/v1/auth/password-setups` on the Tenant Origin to Gateway rather than the SPA fallback. This independent page submits to its own Origin; other Console API calls use the configured API Origin through the shared Client.
5. Complete migrations, wait for backend readiness, and run the administrator and reserved service Client bootstrap tasks below. Tenant operations additionally require a Tenant, Membership, and valid password; a fresh environment does not create this business data automatically.

The browser and shared Client handle cookies, Origin, and Fetch Metadata according to the protocol; UI users do not copy Tokens or cookies. HTTP port `8080` is a local backend port, not a product console. See the [deployment documentation](../../docs/14-deployment.md) for the complete boundary.

### Isolated browser acceptance

The repository provides a [Console authentication acceptance script](../../scripts/verify-console-authentication-e2e.sh) and a [dedicated Compose override](console-authentication.override.yaml) for automated checks against a fresh environment. They do not retain an environment for manual exploration and should not be used directly as the default development stack configuration.

Prepare the local DNS entries, trusted certificate, an available `127.0.0.1:443`, Node `24.14.1`, pnpm `11.22.0`, Docker, OpenSSL, Ruby, and Console dependencies. Default local acceptance also requires Playwright Chromium, WebKit, and Chrome to be available and trust the certificate. From the repository root, run:

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/local-cert.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/local-key.pem
bash scripts/verify-console-authentication-e2e.sh --preflight
# After preflight succeeds, build and run full acceptance.
bash scripts/verify-console-authentication-e2e.sh
```

The certificate must cover all three local hosts; replace the example absolute paths with actual files. Preflight checks the environment, not successful login. The full script creates an isolated project and fresh volumes, prepares test accounts, and drives browsers. It then removes its project, volumes, and temporary Secrets, retaining no accounts or environment for later manual login. Only the output of the current run establishes its result.

## Fresh-volume Tenant lifecycle acceptance

Run the one-shot acceptance script from the repository root. Every run creates an isolated Compose project, random host ports, temporary Secrets, and fresh PostgreSQL, Redis, and Kafka volumes; it does not read or modify `deploy/compose/.env` or development-stack data:

```bash
bash scripts/verify-tenant-lifecycle-e2e.sh
```

The script builds the current source, explicitly bootstraps the Platform Admin and three reserved service clients, then verifies the initial password change, Quota/Plan setup, PENDING Tenant, Subscription, administrator initialization, Mailpit Password Setup, and Tenant-context login. It also covers missing platform role, wrong scope, unavailable IAM, exhausted quota, expired Tenant, credential conflict, cross-Tenant RLS, and plaintext-sensitive-data boundaries. The temporary Compose project, volumes, and Secrets are removed on both success and failure; never copy temporary credentials into logs or the repository.

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

### 4. Log in through Platform Console with the initial password

After meeting the browser prerequisites above, open the [local Platform Console](https://platform.saasforge.test/), enter the bootstrap administrator email and initial password, and select “登录” (Log in). This creates only a restricted session and should open “设置新密码” (Set a new password); platform management remains unavailable at this point.

If the initial password has expired and no regular password exists, use the restricted initial-credential reset below rather than rerunning account creation. If the UI reports an active session in the slot, follow its prompt to log out of that Platform session first.

### 5. Set the regular password in the UI

Enter the regular password on “设置新密码” and select “更新密码” (Update password). The password must meet all of these rules:

- At least 12 Unicode code points;
- At most 128 Unicode code points and at most 512 UTF-8 bytes;
- No spaces, line endings, tabs, or other Unicode whitespace;
- Must not match the system's compromised-password blocklist.

Success displays “密码已更新，请使用新密码重新登录。” (Password updated; log in with the new password). The initial password and restricted session are invalidated. After confirming success, remove the expired initial-password file from the Compose directory:

```bash
rm .secrets/platform-admin-password
```

For a custom Secret path, remove the corresponding old file. Do not remove active service Client Secrets or signing private keys.

### 6. Log in again and check the session

1. Enter the administrator email and regular password on the login page. Successful login opens “Platform 总览” (Platform overview), which currently shows only authentication status.
2. Reload and confirm that session recovery returns to the home page. If a network failure leaves recovery uncertain, use “重试恢复” (Retry recovery).
3. Select “退出登录” (Log out) and confirm the login page appears. Retry through the UI if logout fails. Reloading should not restore the ended Platform session.

These UI actions call the formal APIs; manual login/password-change requests and Access Token inspection are unnecessary. Never write passwords, Tokens, or cookies to `.env`, Git, logs, or chat messages. The `OAuth Client` menu is still a placeholder and cannot create, rotate, or revoke Clients.

## Explicit reserved service OAuth Client bootstrap

Generate three deployment-local fixed Client IDs and Secrets from the Compose directory:

```bash
./generate-service-client-secrets.sh
```

The script uses `openssl` to generate UUIDv7 Client IDs and 256-bit random Secrets, applies `umask 077`, and refuses to overwrite existing files. Then run the explicit one-shot task:

```bash
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
```

The first run creates all three fixed service identities atomically. After formal rotation, reruns only validate Client ID, service key, fixed scopes, and a mounted Secret matching any currently valid Secret. Expired or revoked mounted Secrets require the external file to be updated; a revoked Client requires the Replacement Job and is never modified or restored by bootstrap. Normal startup does not execute this task, and each runtime service mounts only its own Client ID and Secret. No Secret value is stored in source, images, Compose values, or Nacos configuration.

### Replace a revoked reserved Client

Write a newly generated 256-bit Secret to a restricted single-line file, then provide a canonical UUIDv7 request ID, service key, old Client ID, and new UUIDv7 Client ID:

```bash
export IAM_RESERVED_CLIENT_REPLACEMENT_REQUEST_ID=<uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_SERVICE_KEY=IAM
export IAM_RESERVED_CLIENT_REPLACEMENT_OLD_CLIENT_ID=<revoked-client-uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_NEW_CLIENT_ID=<new-client-uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_SECRET_FILE=.secrets/replacement-client-secret
docker compose --profile service-client-replacement run --rm iam-reserved-service-client-replacement
```

The service key is limited to `IAM`, `TENANT_ACCESS`, or `ENTITLEMENT`; name and scopes are derived from it and cannot be supplied. An exact replay returns `ALREADY_REPLACED`; rebinding the same request ID to different inputs fails for manual handling.

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

## Stop, clean up, and redeploy

Run these commands from `deploy/compose`; they apply to the default development stack described here. Confirm the target first:

```bash
docker compose ls
docker compose ps --all
```

If the previous deployment used `-p`, `--env-file`, or additional `-f` files, use the same arguments when inspecting, stopping, removing, and starting it. Otherwise, you may target the wrong project or leave old containers behind. The signing-key initialization script below supports the corresponding `COMPOSE_PROJECT_NAME`, `LOCAL_COMPOSE_ENV_FILE`, and `LOCAL_COMPOSE_OVERRIDE_FILE` environment variables; set them consistently for custom projects. Do not substitute global `docker system prune` or `docker volume prune` for project-specific cleanup.

### 1. Pause without redeploying

```bash
docker compose stop
# Resume the existing containers later.
docker compose start
```

These commands retain containers and data. They neither rebuild images nor apply source or Compose configuration changes.

### 2. Rebuild and redeploy while retaining business data

Use this procedure to deploy updated source or deployment configuration. Migrations may change existing database structures; back up any data you need and confirm that it can be restored first.

```bash
docker compose config --quiet
docker compose down
docker compose up --build -d
docker compose ps --all
```

Without `--volumes`, the PostgreSQL, Redis, and Kafka named volumes remain. Existing platform accounts, regular passwords, and Tenant data remain usable, and migrations run before services start. Do not recreate the Platform Admin or regenerate active service Client Secrets, signing private keys, or database passwords. Investigate Flyway checksum mismatches against migration history rather than deleting volumes, rewriting history, or disabling validation to make startup succeed.

The default Compose stack has no persistent volumes for Nacos or Mailpit. Recreating their containers reinitializes Nacos through `nacos-init` from repository configuration and loses old Mailpit messages. Save required Nacos changes through the [Nacos management process](../nacos/README.md) first. Resend missing password-setup emails through the formal API rather than bootstrapping the administrator again.

### 3. Delete local business data and initialize from scratch

Use this only for disposable local development data. For a code update alone, use the previous section.

> [!CAUTION]
> The following `down --volumes` deletes this Compose project's PostgreSQL, Redis, and Kafka named volumes, including all accounts, Tenants, subscriptions, audit records, sessions, and messages. Back up required data and confirm it is recoverable first; restarting cannot recover deleted data.

```bash
docker compose down --volumes
```

This does not remove host `.env`, `.secrets/`, external Secrets, TLS certificates, frontend `dist` directories, or built images. An empty database does not mean credential files have been removed. Before initializing again:

- Retain and review `.env`; do not overwrite it with `.env.example`.
- Complete sets of the three service Client ID/Secret pairs can be reused for this reset local environment. Rerun the service Client bootstrap below to register them in the new database. Run `./generate-service-client-secrets.sh` only when all six files are absent; it refuses to overwrite files. Restore complete material if some files are missing rather than mixing old and new files.
- The local IAM signing private key can be retained. The initialization script registers matching Signing Key metadata in the new database. If retaining the database, never force regeneration by deleting its private key.
- Check the administrator email file and generate a new random initial password for this deployment. The example below uses default Secret paths; use the actual configured files for custom `.env` paths. If the email file is absent, create it using the earlier Secret-file instructions first.

```bash
mkdir -p .secrets
test -s .secrets/platform-admin-email
openssl rand -base64 32 > .secrets/platform-admin-password
chmod 600 .secrets/platform-admin-email .secrets/platform-admin-password
```

Once all files are ready, run these steps in order. Resolve any failure before continuing with bootstrap or login:

```bash
docker compose config --quiet
docker compose build
bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
docker compose --profile bootstrap run --rm iam-platform-admin-bootstrap
docker compose up -d
docker compose ps --all
```

Because the database was reset, bootstrap the administrator again and change its initial password within 24 hours. The previous regular password no longer works. Recreate Tenants, Memberships, Quota/Plan, Subscriptions, and Tenant administrators through backend APIs; the current frontend cannot create this data.

### 4. Update the frontends and verify the deployment

The default Compose stack does not redeploy frontends or the TLS reverse proxy, whether data is retained or reset. If frontend source changed, rebuild after preparing the Console toolchain and dependencies:

```bash
(cd ../../consoles && corepack pnpm run build)
```

Publish the two new `dist` directories to their original Platform and Tenant sites, and replace each `runtime-config.json` again: every new build includes the intentionally invalid deployment template. Check HTTPS, Gateway proxying, and Password Setup routes against the browser prerequisites above. Redeployment alone does not require deleting trusted TLS certificates.

Then check:

1. Migration jobs show `Exited (0)` in `docker compose ps --all`, and backend services are ready. A running container alone does not prove API availability.
2. Close old Console tabs and reopen Platform Console. Use the existing regular password if data was retained; after a reset, use the new initial password, change it, and log in again.
3. Reload the home page to check session recovery, then log out and reload to confirm that the ended session is not restored.
4. Before testing Tenant login and switching, confirm that Tenant data was retained or recreated. Old Password Setup links cannot be used after the database is reset.

The isolated Console acceptance script removes its own temporary environment. It does not redeploy the development stack, and its test accounts cannot be used to log in to that stack.
