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
