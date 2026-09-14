# Soybean Admin Element Plus

- Repository: https://github.com/soybeanjs/soybean-admin-element-plus
- Commit: `7613bd206cd42001b40e3eafceeb895dcbc277a8`
- License: MIT, retained in `LICENSE`.
- `materials/libs/admin-layout` and `materials/types/index.ts` come from `packages/materials/src`.
- `reset.css` comes from `src/styles/css/reset.css`.

The upstream layout source is retained rather than reimplementing its sizing, scrolling, and collapse behavior. Product navigation and runtime state are supplied through its slots. Demo routes, token storage, mock APIs, charts, editors, and upstream Git hooks are not imported.

`../../layouts/ConsoleLayout.vue` adapts the default vertical layout's header, logo, and Element Plus menu composition from `src/layouts/modules`. `../../styles.css` retains the default container/background/shadow values from `src/theme/settings.ts`; it does not yet implement the complete upstream theme settings UI or the product's controlled brand adaptation.
