# Shared Console Modules

[简体中文](README.md)

Shared foundations for the two SaaS Forge Consoles, built on Vue 3 and Element Plus. These packages live inside the single `consoles/` pnpm workspace; `shared/` is neither a separate workspace root nor a deployable console.

- `api-client`: a stateless typed Client generated from the formal OpenAPI contracts.
- `app-runtime`: authentication, session coordination, business calls, and recovery of the original operation; it does not depend on a UI framework.
- `admin`: the pinned Soybean Admin Element Plus layout, authentication UI, controlled branding, and route-exit guards.
- `i18n`: the language registry, ICU messages, and formatting.

Consoles call formal APIs through the Runtime, and credentials stay in the Runtime's private memory. Business pages use Element Plus directly. Remotes receive the host's locale and inherit its theme; they do not own authentication, branding resolution, or browser preference storage.

## Workspace packages

`consoles/` holds seven packages:

| Directory                                                                                          | Package                              | Scope                                                                                                                         |
| -------------------------------------------------------------------------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| `platform-console/`                                                                                | `@saas-forge/platform-console`       | Platform administration entry point with a fixed `PLATFORM` authentication intent                                             |
| `tenant-console-shell/`                                                                            | `@saas-forge/tenant-console-shell`   | Tenant application host with a fixed `TENANT` intent, Membership selection, Tenant Context switching, and controlled branding |
| [`shared/admin/`](admin/)                                                                          | `@saas-forge/admin`                  | Pinned Soybean Admin Element Plus layout, authentication UI, locale, validated branding, and route-exit guards                |
| [`shared/api-client/`](api-client/)                                                                | `@saas-forge/api-client`             | Stateless typed Client generated from the formal OpenAPI contracts                                                            |
| [`shared/app-runtime/`](app-runtime/README.md)                                                     | `@saas-forge/app-runtime`            | Runtime Config, bootstrap, authentication state machine, session coordination, and controlled typed API calls                 |
| [`shared/i18n/`](i18n/)                                                                            | `@saas-forge/i18n`                   | Language registry, ICU messages, and formatting                                                                               |
| [`business-remotes/admin-consumer-fixture/`](../business-remotes/admin-consumer-fixture/README.md) | `@saas-forge/admin-consumer-fixture` | Remote fixture verifying that shared components inherit the host theme and locale; not a product Remote                       |

## Development and verification

Run every command from `consoles/`, and pick the package checks appropriate to the change:

```bash
pnpm run generate:api
pnpm --filter @saas-forge/api-client run typecheck
pnpm --filter @saas-forge/app-runtime run verify
pnpm --filter @saas-forge/i18n run verify
pnpm --filter @saas-forge/admin run typecheck
pnpm --filter @saas-forge/admin run test
pnpm --filter @saas-forge/admin run test:browser
pnpm --filter @saas-forge/admin-consumer-fixture run verify
pnpm run verify:workspace
```

`api-client` exposes only `typecheck`. `app-runtime` and `i18n` cover types, unit tests, linting, and formatting through `verify`. `admin` splits those concerns across `typecheck`, `test`, `test:browser`, `build`, and `lint`. The fixture verifies types and a production build. `verify:workspace` runs the full pipeline without regenerating the client; use `pnpm run verify` when the client has not been generated yet. Do not install dependencies separately inside `shared/`.

Mocked HTTP and layout fixtures do not replace real business acceptance over trusted HTTPS and live backend services.

## Further reading

- [Console workspace guide](../README-en.md)
- [Shared Console foundation](admin/README.md)
- [`@saas-forge/app-runtime`](app-runtime/README.md)
- [Console Authentication Runtime design](../../docs/28-console-authentication-runtime.md)
- [Real Console authentication acceptance](../../docs/acceptance/issue-115-console-authentication.md)
