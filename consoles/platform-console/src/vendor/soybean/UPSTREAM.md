# Soybean Admin Element Plus

- Repository: https://github.com/soybeanjs/soybean-admin-element-plus
- Commit: `7613bd206cd42001b40e3eafceeb895dcbc277a8`
- License: MIT; original notice retained in [LICENSE](LICENSE).

## Source map

The Platform application adapts the pinned application's directory structure and compositions:

| Platform source (relative to `src`) | Pinned upstream source | Adaptation |
| --- | --- | --- |
| `views/_builtin/login/index.vue` | `src/views/_builtin/login/index.vue` | Keep wave background, centered 400px content, 12px card, 64px logo, brand header and form spacing. SaaS Forge brand, accessible heading, explicit imports and runtime status replace demo module selection. |
| `views/_builtin/login/modules/pwd-login.vue` | `src/views/_builtin/login/modules/pwd-login.vue` | Large form inputs and full-width round submit; real email/password and initial-password change. No remembered password, demo accounts, registration, code login, or forgot-password entry. |
| `components/custom/wave-bg.vue` | `src/components/custom/wave-bg.vue` | Original SVG geometry and placement; native CSS color mixing instead of the upstream color utility dependency; decoration hidden from assistive technology. |
| `layouts/base-layout/index.vue` | `src/layouts/base-layout/index.vue` | Official AdminLayout composition with GlobalHeader, GlobalSider, GlobalTab, GlobalContent and GlobalFooter; fixed default vertical layout (56/44/220/64/48px). |
| `layouts/modules/*` | `src/layouts/modules/*` | Product routes replace demo menus; theme and locale controls retained. Session logout replaces demo user menu. Tab links are keyboard accessible. Content is not cached, preserving one-time secrets and form disposal. |
| `views/home/index.vue`, `views/home/modules/header-banner.vue` | `src/views/home/index.vue`, `src/views/home/modules/header-banner.vue` | Official greeting card and 14/10-column card composition; real current-session information and product shortcuts replace fabricated statistics, charts, weather and news. |
| `locales/index.ts`, `store/modules/*` | `src/locales/index.ts`, `src/store/modules/*`, `src/theme/settings.ts` | Vue I18n plugin and Pinia application/theme organization. ICU compiler, safe locale fallback and existing Runtime replace demo authentication/storage. |

`materials/libs/admin-layout`, `materials/libs/page-tab`, and `materials/types` are copied from `packages/materials/src`; `reset.css` is copied from `src/styles/css/reset.css`. These are retained verbatim except `page-tab/chrome-tab-bg.vue` (both halves directly reference the same geometry, avoiding stale nested SVG `currentColor` after a theme change), and `page-tab/shared.ts`: its two color helpers use CSS `color-mix`, avoiding the unrelated `@sa/color`/`@sa/utils` dependencies. The adapted files outside this vendor directory are project-owned and are checked by lint and typechecking.

## Intentional product differences

- SaaS Forge name, logo, favicon and blue brand replace Soybean's identity. Dark text uses a lighter brand shade to satisfy contrast requirements.
- Only supported product authentication and routes are exposed. Template demo token persistence and demo API clients are not imported.
- Locale selection retains its accessible combobox and both supported languages; no unrelated layout configuration, search, notifications or demo user-profile actions are exposed.
- The existing business pages temporarily retain `@saas-forge/admin` and its styles/ICU resources. `App.vue` supplies their context with exactly the same Runtime and exit guards as the migrated application. There is no old/new product switch or second mount. These dependencies remain pending later migration tickets under #190.

Source derivation and build success alone do not constitute the fixed-version visual or real-authentication acceptance required by #191.
