// Copy the published release artifacts next to the prerendered page.
//
// website/downloads/ is the publish target (tools/release/publish_downloads.sh
// writes it) and stays the single source of truth: the container copies dist/,
// which includes this folder verbatim, so nginx serves the same URLs as before.
import { cpSync, existsSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const site = fileURLToPath(new URL("..", import.meta.url));
const from = join(site, "downloads");
const to = join(site, "dist", "downloads");

if (!existsSync(join(from, "versions.json"))) {
  throw new Error(`copy-downloads: ${from}/versions.json is missing`);
}

cpSync(from, to, { recursive: true });
console.log(`copy-downloads: ${readdirSync(to).length} files in dist/downloads`);
