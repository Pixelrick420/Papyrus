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
`web/package.json` is the source of truth for the scripts; `pnpm format` is there too. `.github/workflows/web.yml`
runs lint, type check and build on any change under `web/`, with Node 22.

Screenshot slides live in `static/screenshots/` and are shown by the lazy, natively scrolled carousel; `static/og-image.png`
is the 1200x630 preview image used by social and link previews.

## Deployment

Deploy to Vercel with `web/` as the project root: use the SvelteKit preset, `pnpm build` as the build command and `build`
as the output directory. `@sveltejs/adapter-static` emits plain static files, so no server is needed.

Set `PUBLIC_SITE_URL` in the Vercel project (or in any build that needs a non-default origin) to the canonical origin,
for example `https://papyrus-viewer.vercel.app/`. It is read at build time, normalised to remove trailing slashes, and
substituted into the canonical, Open Graph, Twitter and JSON-LD URLs in `src/app.html`. When unset it defaults to
`https://papyrus-viewer.vercel.app/`.
