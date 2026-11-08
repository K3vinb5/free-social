import type { ReactNode } from "react";
import type { DownloadEntry } from "../downloads";
import { humanSize } from "../downloads";

type TableProps = {
  id: string;
  title: string;
  note?: ReactNode;
  entries: DownloadEntry[];
  empty: string;
};

/**
 * How many rows fit before the pane scrolls. .dl-scroll in public/styles.css
 * carries the matching max-height (one row is ~47px plus a ~27px header; on a
 * phone each row is a card, so the mobile rules use their own height).
 */
const VISIBLE_ROWS = 4;

function DownloadTable({ id, title, note, entries, empty }: TableProps) {
  // Only a list that actually overflows becomes a focusable scroll region, so
  // short tables (Stable has one row) stay out of the tab order.
  const scrolls = entries.length > VISIBLE_ROWS;
  return (
    <div className="dl-block">
      <h3 className="dl-block__h" id={id}>
        {title}
      </h3>
      {note ? <p className="dl-block__note">{note}</p> : null}
      <div
        className="dl-scroll"
        tabIndex={scrolls ? 0 : undefined}
        role={scrolls ? "region" : undefined}
        aria-label={scrolls ? `${title} builds, scrollable list of all published versions` : undefined}
      >
        <table className="dl-table">
          <colgroup>
            <col className="dl-col-v" />
            <col className="dl-col-sha" />
            <col className="dl-col-date" />
            <col className="dl-col-size" />
            <col className="dl-col-get" />
            <col className="dl-col-get" />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">Version</th>
              <th scope="col">SHA</th>
              <th scope="col">Date</th>
              <th scope="col">Size APK / ZIP</th>
              <th scope="col" colSpan={2}>
                Download
              </th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 ? (
              <tr className="dl-empty">
                <td colSpan={6}>{empty}</td>
              </tr>
            ) : (
              entries.map((entry) => (
                <tr key={`${entry.version}-${entry.sha}`}>
                  <th scope="row">{entry.version}</th>
                  <td>
                    <code>{entry.sha}</code>
                  </td>
                  <td>
                    <time dateTime={entry.date}>{entry.date}</time>
                  </td>
                  <td>
                    <span className="dl-size">{humanSize(entry.bytes_apk)}</span>
                    <span className="dl-size">{humanSize(entry.bytes_zip)}</span>
                  </td>
                  <td className="dl-get">
                    <a className="dl-link" href={entry.apk} download>
                      APK
                    </a>
                  </td>
                  <td className="dl-get">
                    <a className="dl-link dl-link--alt" href={entry.zip} download>
                      ZIP
                    </a>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/**
 * The Stable and Nightlies tables. Both are rendered at build time from
 * website/downloads/versions.json — the file tools/release/publish_downloads.sh
 * writes — so publishing a build is just a site rebuild, with no HTML markers.
 */
export function DownloadTables({
  stable,
  nightlies,
}: {
  stable: DownloadEntry[];
  nightlies: DownloadEntry[];
}) {
  // The nightlies' version name is whatever master last bumped to; read it from
  // the data instead of naming a number that goes stale on the next release.
  const nightlyVersion = nightlies[0]?.version ?? stable[0]?.version;
  return (
    <div className="dl-wrap">
      <DownloadTable
        id="dl-stable-h"
        title="Stable"
        entries={stable}
        empty="No stable release published yet."
      />
      <DownloadTable
        id="dl-nightly-h"
        title="Nightlies"
        entries={nightlies}
        empty="No nightly published yet."
        note={
          <>
            Untagged prerelease builds of <code>master</code> — the newest work, built
            automatically. They carry master's version name unchanged
            {nightlyVersion ? <> ({nightlyVersion} in the newest one)</> : null} and are never
            version-bumped; the date and the build id tell them apart.
          </>
        }
      />
    </div>
  );
}
