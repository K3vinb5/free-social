import type { CSSProperties } from "react";

/** 04 / Privacy — the permission ledger and the manifest excerpt. */
export function Privacy() {
  return (
    <section className="section" id="privacy" aria-labelledby="privacy-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">04 / Privacy</p>
          <h2 id="privacy-title">
            No accounts. No analytics. <em>No socket.</em>
          </h2>
          <p className="sec-note">
            This is a permission list, not a policy page. You can check every line of it inside the
            APK you downloaded.
          </p>
        </header>

        <div className="privacy">
          <div className="privacy__side" data-reveal>
            <p className="lede">
              There is no backend to trust, because there is no backend. <em>Nothing about you leaves the phone.</em>
            </p>
            <p>
              Settings live in SharedPreferences on the device. Rule sets are assets inside the
              APK. Every decision is made in-process, from the view tree that is on screen at that
              moment.
            </p>
            <p>
              There is no sign-up, no crash reporter, no analytics SDK and no ad library. <strong>There
              is nothing to delete, because nothing was collected.</strong>
            </p>
            <pre
              className="code privacy__code"
              aria-label="Excerpt from AndroidManifest.xml: SYSTEM_ALERT_WINDOW, USE_EXACT_ALARM, POST_NOTIFICATIONS and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, with no INTERNET permission anywhere."
            >
              <span className="c">{"<!-- AndroidManifest.xml -->"}</span>
              {"\n<uses-permission android:name="}
              <span className="s">{'"…SYSTEM_ALERT_WINDOW"'}</span>
              {" />\n<uses-permission android:name="}
              <span className="s">{'"…USE_EXACT_ALARM"'}</span>
              {" />\n<uses-permission android:name="}
              <span className="s">{'"…POST_NOTIFICATIONS"'}</span>
              {" />\n<uses-permission android:name="}
              <span className="s">{'"…REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"'}</span>
              {" />\n"}
              <span className="no">{"<!-- no android.permission.INTERNET anywhere -->"}</span>
            </pre>
            <p className="privacy__foot">
              No write access, no network stack, no remote anything: the app cannot open a socket,
              so there is nothing to leak.
            </p>
          </div>

          <div className="privacy__ledger" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <dl className="ledger">
              <div className="ledger__row ledger__row--absent">
                <dt className="ledger__k">
                  INTERNET <span className="badge badge--amber">absent</span>
                </dt>
                <dd className="ledger__v">
                  Not requested. No uploads, no sync, no remote config, no usage ping.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">
                  BIND_ACCESSIBILITY_SERVICE <span className="badge">content filter</span>
                </dt>
                <dd className="ledger__v">
                  Walks the view hierarchy while Instagram, YouTube or TikTok is on screen. Other
                  apps' trees are only ever read by the ghost-tree self-check, which asks whether a
                  target is hiding content — nothing is stored.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">
                  BIND_ACCESSIBILITY_SERVICE <span className="badge">block service</span>
                </dt>
                <dd className="ledger__v">
                  Window changes only; content retrieval is switched off in its config. Reads a
                  package name and nothing else.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">SYSTEM_ALERT_WINDOW</dt>
                <dd className="ledger__v">
                  Draws the 2.2-second escape flash over the app. It cannot stack, cannot linger and
                  has no dismiss button.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">REQUEST_IGNORE_BATTERY_OPTIMIZATIONS</dt>
                <dd className="ledger__v">
                  Optional, asked once. Keeps the service from being frozen mid-evening by an
                  aggressive power profile.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">USE_EXACT_ALARM</dt>
                <dd className="ledger__v">
                  Schedule boundaries fire on time — bedtime, wake, wind-down, morning lock.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">POST_NOTIFICATIONS</dt>
                <dd className="ledger__v">
                  Progress and “Stop now” during node capture. Denial is handled in-app; capture
                  still works.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">{"<queries> · 4 packages"}</dt>
                <dd className="ledger__v">
                  Instagram, YouTube, TikTok (global and trill). Used to launch the app while
                  capturing a screen.
                </dd>
              </div>
            </dl>
            <p className="privacy__foot">
              No contacts, no location, no storage, no camera, no microphone, no AD_ID.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
