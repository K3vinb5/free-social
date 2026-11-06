import { existsSync, readFileSync } from "node:fs";
import { extname, join } from "node:path";
import type { ServerResponse } from "node:http";
import { defineConfig, type Connect, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

const root = import.meta.dirname;

const MIME: Record<string, string> = {
  ".apk": "application/vnd.android.package-archive",
  ".zip": "application/zip",
  ".json": "application/json",
};

/**
 * Serve website/downloads/ during `vite dev`, mirroring nginx's `location ^~ /downloads/`
 * (including the apk MIME type). In production the files are copied into dist/ by
 * scripts/copy-downloads.mjs and nginx serves them from the document root.
 */
function serveDownloads(): Plugin {
  return {
    name: "serve-downloads",
    configureServer(server) {
      server.middlewares.use((req: Connect.IncomingMessage, res: ServerResponse, next: Connect.NextFunction) => {
        const url = (req.url ?? "").split("?")[0];
        if (!url.startsWith("/downloads/")) return next();
        const file = join(root, "downloads", decodeURIComponent(url.slice("/downloads/".length)));
        if (!file.startsWith(join(root, "downloads") + "/") || !existsSync(file)) return next();
        res.setHeader("Content-Type", MIME[extname(file)] ?? "application/octet-stream");
        res.end(readFileSync(file));
      });
    },
  };
}

export default defineConfig({
  // website/ is the project root; dist/ is the document root nginx serves.
  root,
  publicDir: "public",
  base: "/",
  plugins: [react(), serveDownloads()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    assetsDir: "assets",
    modulePreload: { polyfill: false },
  },
});
