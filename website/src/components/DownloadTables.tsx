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

function DownloadTable({ id, title, note, entries, empty }: TableProps) {
  return (
    <div className="dl-block">
      <h3 className="dl-block__h" id={id}>
        {title}
      </h3>
      {note ? <p className="dl-block__note">{note}</p> : null}
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
            Untagged prerelease builds of <code>master</code>. Their versionName is never bumped, so
            every entry reads 0.0.0 — the date and the commit identify them.
          </>
        }
      />
    </div>
  );
}
