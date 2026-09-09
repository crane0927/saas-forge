# SaaS Forge Consoles

[简体中文](README.md)

The SaaS Forge frontend workspace: two independently deployed React consoles sharing an authentication runtime, React shell, Design System, and generated API client.

- **Platform Console**: the platform administration entry point for SaaS providers, with a fixed `PLATFORM` authentication intent.
- **Tenant Console Shell**: the application host for tenant administrators, with a fixed `TENANT` intent, Membership selection, Tenant Context switching, and controlled branding.
- **Shared foundations**: strict runtime configuration, session recovery, login, initial password change, logout, cross-tab session coordination, and consistent components and interactions.

> [!NOTE]
> The current implementation primarily delivers console hosts and authentication. Platform `/` is an overview page and `/oauth-clients` remains a placeholder; Tenant `/` is a workspace page. Product business Remotes, Manifest, and Module Federation are not integrated. Existing routes do not imply complete business administration features.

## Quick start

### Prerequisites

| Tool                | Requirement                          | Purpose                                        |
| ------------------- | ------------------------------------ | ---------------------------------------------- |
| Node.js             | `24.14.1`                            | Frontend development and verification          |
| pnpm                | `11.22.0`, enabled through Corepack  | The workspace's only package manager           |
| JDK                 | `17`; repository CI also checks `21` | TypeScript API client generation through Maven |
| Playwright Chromium | Install before browser verification  | Required by `verify`                           |

This directory is the only pnpm workspace root. Dependencies use the [default Catalog](pnpm-workspace.yaml), with resolved versions locked in `pnpm-lock.yaml`. The repository includes a Maven Wrapper; no separate Maven installation is required.

From the repository root, install dependencies and run frontend verification:

```bash
cd consoles
corepack enable
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
pnpm run verify
```

Linux CI uses `pnpm exec playwright install --with-deps chromium` to prepare browser system dependencies. Initial installation and API client generation require access to the relevant dependency repositories.

### Start development servers

Run these commands from `consoles/`, using separate terminals when developing both applications:

```bash
pnpm run dev:platform
```

```bash
pnpm run dev:tenant
```

Both commands generate the API client before starting the corresponding Vite server. Use the address printed in the terminal. To explore shared components separately, start the Design System showcase:

```bash
pnpm --filter @saas-forge/design-system run dev:showcase
```

> [!IMPORTANT]
> Development servers serve only the frontend; they do not start Gateway, IAM, or databases. Their `/runtime-config.json` supplies the fixed API Origin `https://api.saasforge.test`. Real authentication also requires trusted HTTPS, correct DNS resolution, Gateway security configuration, and provisioned accounts. Default HTTP localhost pages are not a substitute for controlled browser Origins. See the [Compose deployment guide](../deploy/compose/README-en.md) for environment setup.

### Controlled HTTPS Console development entrypoint

On macOS Docker Desktop, run setup once from the repository root, then choose a Console explicitly:

```bash
bash scripts/local-development.sh setup
pnpm --dir consoles run build:static-remote
bash scripts/local-development.sh doctor
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh status
bash scripts/local-development.sh frontend stop all
```

`setup` reuses a valid local CA and reissues the leaf only when a controlled Host is missing, expiry is within 24 hours, or chain/key validation fails. The certificate covers `platform.saasforge.test`, `console.saasforge.test`, `api.saasforge.test`, and `remote.saasforge.test`. Existing two-/three-Host installations must rerun setup. Hosts and Keychain changes still require separate explicit interactive authorization; existing configuration is skipped idempotently, and noninteractive system changes are refused.

Every `frontend` invocation requires `start|status|stop` and `platform|tenant|all`. Platform Vite binds to `127.0.0.1:5173` and Tenant to `127.0.0.1:5174`, with strict ports, their respective controlled Hosts, and HMR over each HTTPS Origin's WSS port 443. Edge reaches both loopback Vite servers through `host.docker.internal`, forwards API traffic to the current Gateway, and preserves browser security headers. Unknown Hosts are rejected. Do not widen Vite listeners to all interfaces to work around Docker Desktop connectivity failures.

Start uses Node `24.14.1`, pnpm `11.22.0`, and existing dependencies, reusing a healthy compatible Edge. Daily start/stop never generates certificates, changes hosts/trust, installs dependencies, generates the API client, starts backend services, or resets accounts. An incompatible Edge occupying 443 blocks start; stop both Consoles before upgrading an old Edge and restarting.

`start all` snapshots both Consoles and Edge and runs preflight before starting resources; it succeeds only after both formal HTTPS Hosts are ready. Healthy processes and a compatible Edge are reused without restart. A failed step rolls back only processes and Edge started by that invocation. `stop all` checks both targets before stopping managed Vite processes and the current project Edge; unknown PIDs, port ownership, or invalid Edge configuration block changes.

`frontend status all` reports both fixed ports, HTTPS readiness, and shared Edge without mutation. Top-level `status` prints these first, then runs the existing five backend checks. Normal `STOPPED`, safely identified `STALE`, and transient `STARTING` states do not fail frontend aggregation. `UNREADY`, `UNMANAGED`, Edge `INVALID`, or `UNAVAILABLE` return nonzero. Status output contains no credentials or raw environment-variable values.

Each Console has its own `platform-vite.pid|log` or `tenant-vite.pid|log` under the Git-ignored `deploy/compose/.secrets/local-https-development/` directory. Both PID files and append-only logs use mode 0600. Status is `RUNNING`, `STOPPED`, `STARTING`, `STALE`, `UNMANAGED`, or `UNREADY`. RUNNING requires matching PID, process group, start time, repository, package identity, loopback listener, and formal HTTPS readiness. Stop sends SIGTERM only to a matching target. Edge is retained while another Console is active or has an unknown identity; stopping the last Console only stops the Edge container without deleting containers, backend services, or volumes. Only Platform adopts a matching legacy `vite.pid`, retaining its old log. Stale records are cleaned only after confirming the original process no longer exists.

The package-level `pnpm --filter @saas-forge/tenant-console-shell run dev` command remains available for foreground debugging. If it occupies 5174, managed lifecycle reports UNMANAGED and refuses to terminate it. Foreground HTTP debugging is not controlled HTTPS acceptance.

On the Tenant Origin, `/password-setup`, `/password-setup/app.js`, `/password-setup/styles.css`, and `/api/v1/auth/password-setups` route exactly to the active Gateway, preserving query strings and browser request headers. Gateway controls page and asset content types, cache headers, and API error responses. Other Tenant paths and HMR continue to reach Tenant Vite.

These paths share the active target file with the API Host. After `bash scripts/local-development.sh replace gateway`, they follow the local Gateway; after `restore gateway`, they return to the container without changing the browser URL or restarting Edge. A missing or invalid target file or an unreachable target returns 502, with no fallback to Vite or another Gateway; unknown Hosts return 421. When first upgrading these routes, stop both Consoles and start them again as described above to load the new Edge script.

Prepare accounts, Gateway, and backend services separately. This routing capability does not constitute complete Tenant authentication acceptance.

#### Fourth-domain static resource acceptance

The development Tenant entry `https://console.saasforge.test/acceptance/static-remote` loads two built Remote versions without joining product navigation. Run `pnpm --dir consoles run verify:local:static-remote` to verify module execution, CSS, images, credential-free CORS, and Vite WSS connections in normally trusted Chromium. Sanitized evidence goes to `.scratch/issue-156/`. See the [fourth-domain development guide](../docs/local-static-remote-development.md) for setup, controlled Edge upgrades, frozen versions, and E2E reuse boundaries. This does not accept a Manifest, business Remote, or parent specification #155 as a whole.

#### States and recovery

The old argument-free `bash scripts/local-development.sh frontend` command has been removed and returns a usage error. These nine commands are its complete replacements, run from the repository root:

```bash
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend status platform
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh frontend stop all
```

| State       | Meaning                                                                             | Recovery action                                                                                                                                                                                    |
| ----------- | ----------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RUNNING`   | Managed identity, loopback listener, and formal HTTPS readiness pass                | Develop normally; repeated start reuses resources                                                                                                                                                  |
| `STOPPED`   | No managed record or listener on the target port                                    | Explicitly start when needed                                                                                                                                                                       |
| `STARTING`  | Managed process exists but has no listener within the 30-second startup window      | Wait and check status again; avoid concurrent repeated starts                                                                                                                                      |
| `STALE`     | A record exists, but its original process is gone and the port is unused            | Stop the target to clean safely, then start                                                                                                                                                        |
| `UNMANAGED` | Untrusted process identity or an unknown port owner                                 | Inspect with `lsof -nP -iTCP:5173 -iTCP:5174 -iTCP:443 -sTCP:LISTEN`; have the original operator end their foreground command, then check status. Never kill by port or simply delete the PID file |
| `UNREADY`   | Managed process exists, but listener startup timed out or formal HTTPS is not ready | Check its log and run doctor; resolve Edge/certificate/dependency issues, then explicitly stop and start                                                                                           |

Logs are `deploy/compose/.secrets/local-https-development/platform-vite.log` and `tenant-vite.log`. Inspect locally; do not copy potentially sensitive raw logs into reports. For Edge `UNAVAILABLE`, check Docker Desktop and Docker access permissions first. For `INVALID`/`UNMANAGED`, establish project ownership and configuration before acting; never automatically replace an unknown listener. Doctor recovery guidance does not authorize changes to certificate trust or backends during acceptance.

For direct package-level foreground debugging, use separate terminals in `consoles/`:

```bash
pnpm --filter @saas-forge/platform-console run dev
pnpm --filter @saas-forge/tenant-console-shell run dev
```

Package commands do not generate the API Client; workspace `dev:platform`/`dev:tenant` generate it before starting. Neither participates in managed PID lifecycle; end each with Ctrl-C in its original terminal. A rendered HTTP localhost page proves only frontend rendering, not login, Cookie, CSRF, or TLS security acceptance.

#### Dual-Console product-path acceptance and restoration

1. Before acceptance, save `frontend status all`, top-level `status`, the current project's Edge container identity and running state, and backend container start times. Confirm existing trusted certificates, hosts, dependencies, and ready backends. Do not run setup, bootstrap, backend replace/restore, or password resets in this run.
2. Cover Platform-only, Tenant-only, all, single-target stop, all stop, and repeated operations, reading aggregate status after each step. A single-target stop must retain Edge while the other Console uses it. Frontend stop does not stop backends, delete containers, Secrets, or volumes, or terminate unknown listeners.
3. In one browser context, open `https://platform.saasforge.test` and `https://console.saasforge.test` with normal certificate verification. Check page identity, meaningful content, error overlays, console/network, and record actual `/api/*` methods, sanitized paths, and status codes for each Console; the API Origin is `https://api.saasforge.test`. Never record passwords, Cookies, Tokens, or sensitive response bodies.
4. Use existing accounts or sessions to log in/restore both slots. Refresh one while the other remains usable; log out of one and verify that the other remains signed in after refresh, then reverse roles. Missing login prerequisites are blockers, not permission to create accounts or reset credentials.
5. Open the Password Setup document on the Tenant Origin and verify its script/style resources and an actual form submission reaching the current Gateway. Exercise only a failure path that does not change a password; record a blocker if no safe submission is possible, and do not consume a valid Challenge. An error response proves routing only, not successful password setup.
6. Temporarily change one visible development marker in each Console, observe its controlled WSS Origin connection and HMR update, and restore the files. Verify host listeners bind only to `127.0.0.1` on 5173/5174, reach both from Edge through `host.docker.internal`, and prove direct access via the host LAN address fails. Stop acceptance if Docker Desktop cannot reach loopback Vite; never fall back to `0.0.0.0`.
7. On success or failure, restore development markers and the original managed frontend combination. If only Edge was initially running, frontend stop also stops it: verify the original container identity, then start only that Edge container (`docker start <verified-original-Edge-container-ID>`). Do not force-kill unknown or abnormal processes to restore state. Finally, recheck frontend/Edge states and backend identities/start times read-only, recording any unrestored differences.

Report passed, failed, and blocked checks separately. Script tests or earlier Platform evidence are not Tenant real-machine evidence, and acceptance does not rewrite the historical scope of #126/#131.

## Workspace structure

| Directory                                                                                                       | Responsibility                                                                                                       |
| --------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| [`platform-console/`](platform-console/)                                                                        | Independent Vite + React platform application with local routes and a fixed authentication intent                    |
| [`tenant-console-shell/`](tenant-console-shell/)                                                                | Independent tenant application host connecting Tenant Context, navigation, and branding                              |
| [`shared/api-client/`](shared/api-client/)                                                                      | Stateless TypeScript REST client with a stable public package entry                                                  |
| [`shared/app-runtime/`](shared/app-runtime/README.md)                                                           | React- and router-independent configuration, bootstrap, authentication state machine, and controlled typed API calls |
| [`shared/react-shell/`](shared/react-shell/)                                                                    | Shared authentication pages, protected routes, navigation, recovery/retry UI, and layered error boundaries           |
| [`shared/design-system/`](shared/design-system/README.md)                                                       | The sole public UI package: themes, semantic tokens, layouts, forms, tables, and interaction rules                   |
| [`business-remotes/design-system-consumer-fixture/`](business-remotes/design-system-consumer-fixture/README.md) | A Remote fixture for shared UI consumer verification, not a product Remote                                           |
| `test/`, `browser-test/`, `integration-test/`                                                                   | Workspace boundary, browser consumer, and session/product integration tests                                          |

### Development boundaries

- **API generation**: Maven/OpenAPI Generator is the sole generator. It reads [`contracts/openapi/`](../contracts/openapi/) and writes to the Git-ignored `shared/api-client/.generated/`. Do not edit generated files or import that directory directly; use the public `@saas-forge/api-client` entry.
- **Authentication and HTTP**: pages and Remotes reuse the host runtime and call formal API operations through its controlled typed client. They must not create separate authentication state, read tokens, or inject Cookie, Origin, Fetch Metadata, or Bearer Token headers. Access tokens are not persisted. The generated client itself does not manage sessions, CSRF, or token storage.
- **Shared UI**: each Console entry installs exactly one `DesignSystemProvider`. Consumers import only from the `@saas-forge/design-system` root. Direct `antd` dependencies, internal imports, global CSS injection, internal selector overrides, and copies of existing public components are prohibited. CSS Modules may arrange domain-specific content.
- **Fail-closed configuration**: Runtime Config is validated before authentication and application routing. Failures expose only safe error codes and explicit retry, never a guessed fallback API address.

## Commands and verification

Run all commands below from `consoles/`.

| Command                                   | Scope                                                                                                            |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `pnpm run generate:api`                   | Generate the API client through Maven                                                                            |
| `pnpm run typecheck`                      | Recursive strict TypeScript checks, including the generated client                                               |
| `pnpm run lint` / `pnpm run format:check` | ESLint / Prettier checks for handwritten sources and documentation, excluding generated output                   |
| `pnpm run test`                           | Static workspace boundaries and package tests, excluding the root browser suite                                  |
| `pnpm run test:browser:chromium`          | Chromium tests for the Design System, consumers, and cross-tab sessions                                          |
| `pnpm run test:browser:compatibility`     | Chrome, Edge, Firefox, and WebKit compatibility tests, in sequence                                               |
| `pnpm run build`                          | Generate the client, build workspace packages, and verify Design System artifact boundaries                      |
| `pnpm run verify`                         | Generate the client, then run the complete frontend verification pipeline                                        |
| `pnpm run verify:workspace`               | The same frontend pipeline without generation, reused by Maven and other flows that already generated the client |

The pipeline runs type checks → ESLint → Prettier → boundary and package tests → Chromium browser tests → production builds and artifact checks. Standalone `typecheck`, `test`, and browser commands do not generate the client; run `pnpm run generate:api` first.

Compatibility tests require their browser installations. Individual commands are also available: `test:browser:chrome`, `test:browser:edge`, `test:browser:firefox`, and `test:browser:webkit`.

```bash
pnpm exec playwright install chrome msedge firefox webkit
pnpm run generate:api
pnpm run test:browser:compatibility
```

Each application's package-level `dev`, `typecheck`, `lint`, `format:check`, `test`, `build`, and `verify` commands operate only on that package. They neither call Maven nor replace workspace verification. Running `./mvnw verify` at the repository root generates the client before invoking `verify:workspace`; Maven does not install Node, pnpm, frontend dependencies, or browsers.

### Verification scope

Workspace checks cover shared package boundaries, UI interactions, session coordination, and static artifact consistency. They are not equivalent to real backend login or deployment acceptance. WebKit provides reproducible Safari-engine compatibility testing, not native Safari testing.

Real Console authentication uses the separate [`verify-console-authentication-e2e.sh`](../scripts/verify-console-authentication-e2e.sh), involving a fresh Compose environment, trusted TLS, and actual service requests. It is not part of `pnpm run verify`. Read the [product acceptance guide and prerequisites](../docs/acceptance/issue-115-console-authentication.md) before running it; historical results there do not establish that your current environment passes.

## Build and deployment

After `pnpm run build`, publish the two independent static artifacts:

- `platform-console/dist/` → Platform Console Origin.
- `tenant-console-shell/dist/` → Tenant Console Origin.

Each artifact contains an intentionally invalid `/runtime-config.json` template. Deployment must atomically replace it with a strict two-field configuration, for example:

```json
{
  "schemaVersion": 1,
  "apiBaseUrl": "https://api.example.test"
}
```

`apiBaseUrl` must be an absolute HTTPS Origin with no credentials, business path, query, or fragment. Configuration must not contain secrets or change application identity, routes, menus, or authorization behavior. Vite's development configuration is not injected into production bundles.

> [!WARNING]
> Every rebuild restores the `REPLACE_DURING_DEPLOYMENT` template, so replace it on every deployment. An unreplaced template leaves the application on its configuration error page; this is intentional fail-closed behavior.

Static hosting must provide SPA fallback for client-side routes while serving `/runtime-config.json` correctly, not as fallback HTML. TLS, CORS, Cookie, and Gateway settings must match both frontend Origins and the API Origin. See the [Compose deployment guide](../deploy/compose/README-en.md) for the topology.

## Troubleshooting

| Symptom                                           | What to check                                                                                                                                                 |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ERR_PNPM_VERIFY_DEPS_BEFORE_RUN`                 | Check Node/pnpm versions and the lockfile, then run a frozen install from `consoles/`. Do not disable `verifyDepsBeforeRun: error` or switch package managers |
| Missing generated client or API types             | Run `pnpm run generate:api` from the workspace root and check JDK/Maven dependency access                                                                     |
| Playwright cannot find a browser executable       | Install the engine or Chrome/Edge channel required by the selected test                                                                                       |
| Application stays on the configuration error page | Inspect the `/runtime-config.json` HTTP response, its two-field JSON contract, and the HTTPS Origin; replace production templates                             |
| Page loads but authentication requests fail       | Check API reachability, certificate trust, entry-point domains, and Gateway security boundaries; a visible page does not prove authentication works           |

## Further reading

- [Repository overview (Chinese)](../README.md)
- [Console Authentication Runtime design](../docs/28-console-authentication-runtime.md)
- [Design System components and consumer rules](shared/design-system/README.md)
- [Compose environment and browser setup](../deploy/compose/README-en.md)
- [Console authentication product acceptance record](../docs/acceptance/issue-115-console-authentication.md)
