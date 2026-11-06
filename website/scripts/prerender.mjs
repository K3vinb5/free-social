// Inject the server-rendered page into the built HTML shell.
//
// Runs after `vite build` (shell + client bundle) and
// `vite build --ssr src/entry-server.tsx` (the render function). The result is a
// plain static index.html: with JavaScript off, the served file already contains
// the whole page.
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const site = fileURLToPath(new URL("..", import.meta.url));
const shellPath = join(site, "dist", "index.html");
const PLACEHOLDER = "__APP_HTML__";

const { render } = await import(new URL("../dist-ssr/entry-server.js", import.meta.url));

const shell = await readFile(shellPath, "utf8");
if (!shell.includes(PLACEHOLDER)) {
  throw new Error(
    `prerender: ${PLACEHOLDER} is not in ${shellPath} — index.html must keep the placeholder in #root`,
  );
}

// React 19 emits <link rel="preload" as="image"> for eager images in front of the
// markup. The page already preloads its fonts explicitly and the remaining images
// sit below the fold, so those links are dropped rather than injected into #root —
// the served HTML then matches the page as designed, and the client is free to
// float its own preloads into <head> once it hydrates.
const appHtml = render().replace(/^(?:<link rel="preload"[^>]*>)+/, "");
if (!appHtml.trim().startsWith("<header")) {
  throw new Error("prerender: the render returned no markup");
}

// Function replacement: keeps `$&`-style sequences in the markup literal.
await writeFile(shellPath, shell.replace(PLACEHOLDER, () => appHtml), "utf8");
console.log(`prerender: ${appHtml.length} bytes of markup injected into dist/index.html`);
