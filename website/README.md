# Free Social — website

The marketing site at <https://freesocial.k3vinb5.dev>. React source, prerendered to
plain static files at build time, served by nginx from the container in this directory.

```bash
bun install
bun run dev      # http://localhost:5173 — live reload; /downloads is served from ./downloads
bun run build    # dist/ — prerendered HTML + hashed assets + downloads
bun run test     # assertions on the last build (run after build)
bun run typecheck
```

## How the static output is produced

Nothing is rendered in the browser to build a page — the served `index.html` already
contains the whole document, so a visitor with JavaScript off sees exactly the same page.

1. `vite build` — the client bundle plus `index.html`, `styles.css` and `assets/`
   (copied verbatim from `public/`).
2. `vite build --ssr src/entry-server.tsx` — the same components compiled for Node.
3. `scripts/prerender.mjs` — `renderToString(<App />)` is injected into the
   `__APP_HTML__` placeholder inside `<div id="root">`. The script fails the build if the
   placeholder is gone.
4. `scripts/copy-downloads.mjs` — `downloads/` is copied next to the page, so the APK,
   the zip and `versions.json` keep their original URLs.
5. `scripts/check-build.mjs` — asserts the prerendered HTML really has every section and
   both download tables, that the table rows match `downloads/versions.json`, and that
   every local `href`/`src` resolves to a file in `dist/`.

Hydration (`src/main.tsx`) exists for one reason: `src/usePageBehaviour.ts`, a port of the
old inline script that toggles the sticky header and reveals `[data-reveal]` blocks with an
`IntersectionObserver`. Styles keep the `html.js` gate, so with JavaScript off the page is
shown fully revealed. The inline `<script>` in `<head>` only adds that `js` class.

## Layout

| Path | What it is |
| --- | --- |
| `src/App.tsx` | page shell: header, `main`, footer |
| `src/components/SiteHeader.tsx` | nav incl. the "Star on GitHub" pill |
| `src/components/Hero.tsx` | hero copy, CTAs, three-phone stage |
| `src/components/DownloadTables.tsx` | Stable / Nightlies tables |
| `src/components/SiteFooter.tsx` | footer |
| `src/components/icons.tsx` | the star glyph |
| `src/sections/WhatItDoes.tsx` | 01 / What it does |
| `src/sections/Day.tsx` | 02 / A day with it (the shared budget) |
| `src/sections/HonestRules.tsx` | 03 / Honest rules |
| `src/sections/Privacy.tsx` | 04 / Privacy |
| `src/sections/Limits.tsx` | 05 / Limits |
| `src/sections/Download.tsx` | 06 / Download |
| `src/downloads.ts` | `versions.json` types, rows and size formatting |
| `public/` | `styles.css`, images, fonts, favicon — served verbatim |
| `downloads/` | published artifacts + `versions.json` (written by `tools/release/publish_downloads.sh`) |

The table data is the only generated content on the page: `downloads/versions.json` is
bundled into both the prerender and the client bundle, so server and client agree.

Release values that are not in `versions.json` — the hero version string, the APK size in
the buttons, the sha256 line in the Download section — are maintained by hand in
`src/components/Hero.tsx` and `src/sections/Download.tsx`.
