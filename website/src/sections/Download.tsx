import { DownloadTables } from "../components/DownloadTables";
import { nightlies, stable } from "../downloads";

/**
 * 06 / Download — the CTA, install steps, the Android 13 note and the build list.
 * The hero APK line and the checksum are hand-maintained with each release;
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
            Free and open source. Tap the APK and allow the install from your file manager. The .zip
            is the same signed build with the install notes inside, if you would rather keep them
            next to the file. Android 12L or newer.
          </p>
          <div className="download__actions">
            <a className="btn btn--lg" href="downloads/free-social-0.4.1-1e7b5a1.apk" download>
              Download the APK
              <span className="btn__meta">23.7 MB</span>
            </a>
            <a className="download__alt" href="downloads/free-social-0.4.1-1e7b5a1.zip" download>
              or the .zip <span aria-hidden="true">·</span> 8.2 MB, APK + install notes
            </a>
          </div>
          <p className="download__meta">
            <span>v0.4.1 · 23.7 MB APK · Android 12L or newer</span>
            <code>APK SHA-256 4f40926c0833eaa21532beaa6ca5493002f5600d884bcdf43b017de575825f93</code>
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
              <b>Enable the filter</b>
              <small>Turn on the content filter service, then set a budget.</small>
            </li>
          </ol>

          <div className="dl-history" data-reveal>
            <h3 className="dl-history__h">Android 13 and newer: one extra switch</h3>
            <div className="rulefix">
              <div className="rulefix__txt">
                <p>
                  Android hides accessibility from apps installed outside the Play Store until you
                  allow it. Open Free Social in Settings → App info, tap the menu in the top-right,
                  choose <b>Allow restricted settings</b>, then turn on the content filter service.
                </p>
                <p>
                  It is one extra tap on an otherwise short install, and Android means it: an app
                  that can read the screen should be a deliberate choice.
                </p>
              </div>
              <figure className="rulefix__shot">
                <div className="phone mini">
                  <img
                    src="/assets/restricted-settings.png"
                    width={820}
                    height={1645}
                    loading="lazy"
                    decoding="async"
                    alt="Free Social setup card: allow restricted settings in Android, with three steps and a button to open app settings."
                  />
                </div>
                <figcaption className="shot-cap">
                  The setup card walks you through Android's extra switch.
                </figcaption>
              </figure>
            </div>
          </div>

          <div className="dl-history">
            <h3 className="dl-history__h">All builds, direct from this page</h3>
            <p className="dl-block__note">
              Every file here is served straight from this site, not through a redirect. The APK
              installs as it is; the .zip is the same APK plus the install notes.
            </p>
            <DownloadTables stable={stable} nightlies={nightlies} />
          </div>
        </div>
      </div>
    </section>
  );
}
