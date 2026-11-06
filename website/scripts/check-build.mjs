// Build assertions for the prerendered site (runs after `vite build` + prerender).
//
// The site's contract is that the served HTML is complete without JavaScript, and
// that the download tables are exactly what website/downloads/versions.json says.
// This script fails the build when either stops being true, so a broken prerender
// can never reach the image.
import { readFileSync, existsSync, statSync } from "node:fs";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const site = fileURLToPath(new URL("..", import.meta.url));
const dist = join(site, "dist");
const html = readFileSync(join(dist, "index.html"), "utf8");
const versions = JSON.parse(readFileSync(join(site, "downloads", "versions.json"), "utf8"));

const failures = [];
const check = (ok, message) => {
  if (!ok) failures.push(message);
};

// ---------------------------------------------------------------- prerender
check(!html.includes("__APP_HTML__"), "index.html still contains the prerender placeholder");
check(
  html.includes('<div id="root"><header class="site-header">'),
  "the page is not prerendered into #root",
);

const sections = [
  "how",
  "features",
  "rules",
  "privacy",
  "limits",
  "download",
];
for (const id of sections) check(html.includes(`id="${id}"`), `section #${id} is missing from the HTML`);
for (const title of [
  "hero-title",
  "how-title",
  "features-title",
  "rules-title",
  "privacy-title",
  "limits-title",
  "download-title",
]) {
  check(html.includes(`id="${title}"`), `heading #${title} is missing from the HTML`);
}

// ------------------------------------------------------------------ tables
check(html.includes('id="dl-stable-h"'), "the Stable table is missing from the HTML");
check(html.includes('id="dl-nightly-h"'), "the Nightlies table is missing from the HTML");
check(
  html.includes("Untagged prerelease builds of <code>master</code>"),
  "the Nightlies explainer is missing from the HTML",
);

const human = (size) => {
  if (size >= 1e9) return `${(size / 1e9).toFixed(1)} GB`;
  if (size >= 1e6) return `${(size / 1e6).toFixed(1)} MB`;
  if (size >= 1e3) return `${Math.round(size / 1e3)} kB`;
  return `${size} B`;
};

const stable = versions.stable ?? [];
const nightlies = versions.nightlies ?? [];
check(stable.length > 0, "versions.json lists no stable release");
if (nightlies.length === 0) {
  check(html.includes("No nightly published yet."), "the empty Nightlies state is missing");
}
for (const [channel, entries] of [
  ["stable", stable],
  ["nightly", nightlies],
]) {
  for (const entry of entries) {
    const label = `${channel} ${entry.version}/${entry.sha}`;
    check(html.includes(`<code>${entry.sha}</code>`), `${label}: sha is not rendered`);
    check(
      new RegExp(`<time [^>]*datetime="${entry.date}"[^>]*>${entry.date}</time>`, "i").test(html),
      `${label}: date is not rendered`,
    );
    check(
      html.includes(`<span class="dl-size">${human(entry.bytes_apk)}</span><span class="dl-size">${human(entry.bytes_zip)}</span>`),
      `${label}: APK/ZIP sizes are not rendered`,
    );
    check(
      new RegExp(`<a [^>]*href="${entry.apk}"[^>]*download[^>]*>APK</a>`).test(html),
      `${label}: downloadable APK link is not rendered`,
    );
    check(
      new RegExp(`<a [^>]*href="${entry.zip}"[^>]*download[^>]*>ZIP</a>`).test(html),
      `${label}: downloadable ZIP link is not rendered`,
    );
  }
}

// ------------------------------------------------------------ local targets
const targets = new Set();
for (const match of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
  const url = match[1];
  if (/^(?:[a-z]+:|\/\/|#)/i.test(url)) continue;
  targets.add(url);
}
for (const target of targets) {
  const file = join(dist, target.split("?")[0]);
  check(existsSync(file) && statSync(file).isFile(), `local target does not exist in dist: ${target}`);
}
for (const page of ["styles.css", "assets/mark.svg", "assets/fonts/roboto.woff2", "assets/fonts/roboto-mono.woff2"]) {
  check(existsSync(join(dist, page)), `${page} is missing from dist`);
}
for (const entry of [...stable, ...nightlies]) {
  for (const field of ["apk", "zip"]) {
    const name = entry[field].replace(/^downloads\//, "");
    check(existsSync(join(dist, "downloads", name)), `downloads/${name} is missing from dist`);
  }
}

// ------------------------------------------------------------------ scripts
const scripts = Array.from(html.matchAll(/<script\b[^>]*src="([^"]+)"/g)).map((m) => m[1]);
check(scripts.length === 1, `expected exactly one script bundle, found ${scripts.length}`);
for (const src of scripts) {
  check(existsSync(join(dist, src)), `script bundle is missing from dist: ${src}`);
  check(extname(src) === ".js", `script bundle is not JavaScript: ${src}`);
}

if (failures.length > 0) {
  console.error(`check-build: ${failures.length} problem(s) in the prerendered site:`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(
  `check-build: ok — ${sections.length} sections, ${stable.length} stable / ${nightlies.length} nightly rows, ${targets.size} local targets`,
);
