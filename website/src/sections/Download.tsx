import { DownloadTables } from "../components/DownloadTables";
import { nightlies, stable } from "../downloads";

/**
 * 06 / Download — the CTA, install steps and the full build history.
 * The hero APK line, sizes and sha256 are hand-maintained with each release;
 * the Stable/Nightlies tables come from downloads/versions.json at build time.
 */
export function Download() {
  return (
    <section className="section download" id="download" aria-labelledby="download-title">
      <div className="shell">
        <div className="download__card" data-reveal>
          <svg className="download__ring" viewBox="0 0 200 200" aria-hidden="true" focusable="false">
            <circle cx="100" cy="100" r="78" fill="none" stroke="rgba(255,178,92,0.22)" strokeWidth={1} />
            <circle
              cx="100"
              cy="100"
              r="58"
              fill="none"
              stroke="rgba(255,178,92,0.5)"
              strokeWidth={1.5}
              strokeLinecap="round"
              strokeDasharray="270 96"
              transform="rotate(-38 100 100)"
            />
            <circle cx="100" cy="100" r="38" fill="none" stroke="rgba(255,178,92,0.16)" strokeWidth={1} />
          </svg>
          <p className="sec-index">06 / Download</p>
          <h2 id="download-title">
            Take the twenty minutes <em>back</em>.
          </h2>
          <ul className="download__stats" aria-label="Zero accounts, zero trackers, zero servers">
            <li>
              <b>0</b>accounts
            </li>
            <li>
              <b>0</b>trackers
            </li>
            <li>
              <b>0</b>servers
            </li>
          </ul>
          <p className="download__p">
            A signed release build. Tap the APK, allow the install from your file manager. The .zip
            holds the same APK plus the README, if you would rather keep the install steps next to
            the file. Android 12L or newer; Android 13+ needs the extra “Allow restricted settings”
            toggle from step two.
          </p>
          <div className="download__actions">
            <a className="btn btn--lg" href="downloads/free-social-0.4.1-1e7b5a1.apk" download>
              Download the APK
              <span className="btn__meta">23.7 MB</span>
            </a>
            <a className="download__alt" href="downloads/free-social-0.4.1-1e7b5a1.zip" download>
              or the .zip <span aria-hidden="true">·</span> 8.2 MB, APK + README
            </a>
          </div>
          <p className="download__meta">
            <span>v0.4.1 · 1e7b5a1 · 23.7 MB APK · minSdk 32</span>
            <code>apk sha256 4f40926c0833eaa21532beaa6ca5493002f5600d884bcdf43b017de575825f93</code>
          </p>
          <ol className="install">
            <li>
              <span>01</span>
              <b>Open the APK</b>
              <small>Tap the download; the browser or file manager hands it over.</small>
            </li>
            <li>
              <span>02</span>
              <b>Allow the install</b>
              <small>Your file manager will ask to install unknown apps.</small>
            </li>
            <li>
              <span>03</span>
              <b>Enable the service</b>
              <small>Allow restricted settings first on Android 13+.</small>
            </li>
          </ol>

          <div className="dl-history">
            <h3 className="dl-history__h">Every build, direct</h3>
            <p className="dl-block__note">
              Every file here is served straight from this site, not through a redirect. The APK
              installs as it is; the .zip is the same APK plus the README.
            </p>
            <DownloadTables stable={stable} nightlies={nightlies} />
          </div>
        </div>
      </div>
    </section>
  );
}
