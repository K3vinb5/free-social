import versions from "../downloads/versions.json";

export type DownloadEntry = {
  version: string;
  sha: string;
  date: string;
  apk: string;
  zip: string;
  bytes_apk: number;
  bytes_zip: number;
};

type VersionsFile = {
  generated?: string;
  stable?: DownloadEntry[];
  nightlies?: DownloadEntry[];
};

const doc = versions as VersionsFile;

/**
 * Written by tools/release/publish_downloads.sh and bundled at build time, so the
 * prerendered HTML and the hydrated client see exactly the same data.
 */
export const stable: DownloadEntry[] = doc.stable ?? [];
export const nightlies: DownloadEntry[] = doc.nightlies ?? [];

/**
 * The newest stable release — the only thing the hero and download CTAs may
 * point at. null only before the first stable publish.
 */
export const latestStable: DownloadEntry | null = stable[0] ?? null;

/** Same formatting the generated tables used: GB / MB / kB / B. */
export function humanSize(size: number): string {
  if (size >= 1e9) return `${(size / 1e9).toFixed(1)} GB`;
  if (size >= 1e6) return `${(size / 1e6).toFixed(1)} MB`;
  if (size >= 1e3) return `${Math.round(size / 1e3)} kB`;
  return `${size} B`;
}
