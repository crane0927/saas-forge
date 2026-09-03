# SaaS Forge Shared Frontend

[简体中文](README.md)

Shared frontend foundations for Platform Console and Tenant Console Shell. Four private workspace packages separate the generated API client, UI-free application runtime, React shell, and public Design System.

See the [Console workspace guide](../README-en.md) for installation, application startup, and deployment configuration. `shared/` is neither a separate pnpm workspace nor another deployable console.

## Packages and responsibilities

| Package                                                | Owns                                                                                                                               | Does not own                                                                               |
| ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| [`@saas-forge/api-client`](api-client/)                | OpenAPI-generated APIs, models, and request runtime types exported through a stable root entry                                     | Authentication state, CSRF policy, token storage, pages, or navigation                     |
| [`@saas-forge/app-runtime`](app-runtime/README.md)     | Runtime Config, bootstrap, authentication state machine, session coordination, and controlled typed API calls                      | React, routing, styles, or visual components                                               |
| [`@saas-forge/react-shell`](react-shell/)              | Authentication pages, Membership selection and switching UI, protected routes, navigation, recovery/retry UI, and error boundaries | Separate authentication state, direct authentication HTTP calls, or product business pages |
| [`@saas-forge/design-system`](design-system/README.md) | A shared provider, themes, semantic tokens, layouts, forms, tables, and accessible interactions                                    | Authentication state, business authorization, product routes, or domain rules              |

> [!NOTE]
> The Runtime and React Shell already handle authentication, refresh, logout, Tenant Context switching, and cross-tab session coordination. Business Remote loading protocols, Manifest, and Module Federation are not implemented here. The existing [Remote fixture](../business-remotes/design-system-consumer-fixture/README.md) verifies only Design System consumer boundaries.

## Dependency direction

This diagram shows direct dependencies between shared packages and their UI implementation. Arrows mean “depends on”:

```text
react-shell ──> app-runtime ──> api-client
     │
     └────────> design-system ──> antd
```

`app-runtime` has no dependency on React, React Shell, or Design System; `design-system` does not depend on the authentication runtime. React and router adapters stay in the upper layer, while API generation details stay below it. Every shared package exposes only its root entry; internal paths are not consumer contracts.

## Usage boundaries

### Host wiring

1. The Console loads and validates `/runtime-config.json` from its own Origin. Configuration contains only `schemaVersion` and `apiBaseUrl`, with an absolute HTTPS API Origin. Failure keeps the application closed and offers explicit retry.
2. After validation, the host calls `createAuthenticationRuntimeAfterConfig` with a fixed `PLATFORM` or `TENANT` intent. The same intent in the same page Realm reuses one runtime instance.
3. Pass that runtime to `AuthenticationShell`, with host-owned local routes and application identity. Pages and Remotes must not create another authentication runtime.
4. Install exactly one `DesignSystemProvider` per Console entry. Apply Tenant branding through the controlled public API, without adding a second provider layer.

See the actual wiring in [Platform Console](../platform-console/src/app.tsx) and [Tenant Console Shell](../tenant-console-shell/src/app.tsx), with provider entries in [Platform main](../platform-console/src/main.tsx) / [Tenant main](../tenant-console-shell/src/main.tsx).

### HTTP and credentials

- Pages and Remotes invoke formal API operations through the host runtime's controlled typed client. They must not instantiate a generated client directly to bypass authentication boundaries.
- The runtime manages access tokens without persisting them. Consumers cannot read tokens, obtain a generic credential-bearing `fetch`, override the API Origin, or inject Cookie, Origin, Fetch Metadata, or Bearer Token headers.
- The browser manages HttpOnly cookies, Origin, and `Sec-Fetch-*`. The runtime owns authentication requests, refresh, CSRF request markers, and normalized failures; these policies do not belong to the generated client itself.

Currently, `runtime.client` exposes `getOAuthClient` and `createOAuthClient`; it is not an arbitrary-URL HTTP proxy. A typed operation does not imply that its product page is complete. Consult the [`app-runtime` public entry](app-runtime/src/index.ts) for the interface.

### Public UI

Import components only from the `@saas-forge/design-system` root. Consoles and Remotes must not depend directly on `antd`, import internal files, inject global CSS, override internal `.ant-*` / `.sf-*` selectors, or copy existing public components. CSS Modules may arrange domain-specific content. When a shared component needs a capability, add the smallest public API to Design System before consuming it.

### Generated code

[`contracts/openapi/`](../../contracts/openapi/) is the API contract source, and Maven/OpenAPI Generator is the sole generator. Its output, `api-client/.generated/`, is Git-ignored and must not be edited or imported directly. Handwritten code references generated APIs and types through the `@saas-forge/api-client` root entry.

## Development and verification

Prepare the pinned Node, pnpm, JDK, and browser dependencies using the [workspace guide](../README-en.md#quick-start). Run all commands below from `consoles/`; do not install dependencies separately inside `shared/`.

Generate the formal API client first, then select package checks appropriate to the change:

```bash
pnpm run generate:api
pnpm --filter @saas-forge/api-client run typecheck
pnpm --filter @saas-forge/app-runtime run verify
pnpm --filter @saas-forge/react-shell run verify
pnpm --filter @saas-forge/design-system run verify
```

`api-client` provides only a type-check command. Runtime and React Shell verification includes types, unit tests, linting, and formatting; Design System also runs browser tests and builds. Package-level commands do not call Maven.

Shared changes also require consumer verification. With the client already generated, run:

```bash
pnpm run verify:workspace
```

This pipeline covers strict types, linting, formatting, workspace boundaries, package tests, Chromium browser tests, and production artifact checks. If the client has not been generated, use `pnpm run verify` instead. See [workspace verification](../README-en.md#commands-and-verification) for Chrome, Edge, Firefox, and WebKit commands.

> [!IMPORTANT]
> Passing package tests does not establish that both Consoles and the Remote fixture pass integration checks. Workspace verification does not replace product acceptance against real services and trusted TLS. Successful client generation or builds are not proof of completed business functionality.

## Further reading

- [Console workspace guide](../README-en.md)
- [Runtime Config and authentication core](app-runtime/README.md)
- [Design System components and consumer rules](design-system/README.md)
- [Console Authentication Runtime design](../../docs/28-console-authentication-runtime.md)
- [Real Console authentication acceptance](../../docs/acceptance/issue-115-console-authentication.md)
