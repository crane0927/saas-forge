# Shared Console foundation

[简体中文](README.md)

Vue 3 and Element Plus with the pinned Soybean layout. See [UPSTREAM.md](src/vendor/soybean/UPSTREAM.md) for provenance and licensing.

`mountConsole` creates a fixed-intent application using the existing authentication runtime and typed Client. It owns authentication UI, tenant switching, password setup, recovery, locale, atomic branding, and unsaved-form guards. Business pages compose Element Plus directly.

Authentication and recovery messages live in separate resource modules. Brand validation preserves controlled paths, MIME and image decoding, contrast, and stale-response protection, with atomic platform fallback.

Run the admin package typecheck, test and test:browser scripts. Formal route regressions live in `integration-test/console-vue-products.test.mjs`; mocked HTTP and layout fixtures do not replace real backend acceptance over trusted HTTPS.
