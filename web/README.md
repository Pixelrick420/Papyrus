# Papyrus website

`web/` is the static landing page that ships separately from the Android app: SvelteKit with Tailwind CSS, built to
static files.

```bash
cd web
pnpm install
pnpm dev      # local preview
pnpm lint     # ESLint
pnpm check    # Svelte and TypeScript checks
pnpm build    # static output in web/build
```

pnpm 12.18.0 is the pinned version (also in `web/package.json` and `.github/workflows/web.yml`); use it locally.
`web/package.json` is the source of truth for the scripts — `pnpm format` is there too. `.github/workflows/web.yml`
runs lint, type check and build on any change under `web/`, with Node 22.
