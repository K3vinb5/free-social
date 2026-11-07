import type { CSSProperties } from "react";

/** 04 / Privacy — what the app can and cannot do, in the order a person cares. */
export function Privacy() {
  return (
    <section className="section" id="privacy" aria-labelledby="privacy-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">04 / Privacy</p>
          <h2 id="privacy-title">
            No account. No cloud. <em>Nothing to delete.</em>
          </h2>
          <p className="sec-note">
            This is a permission list, not a policy page. You can check every line of it in the
            app you downloaded.
          </p>
        </header>

        <div className="privacy">
          <div className="privacy__side" data-reveal>
            <p className="lede">
              There is no server to trust, because there is no server.{" "}
              <em>Nothing about you leaves the phone.</em>
            </p>
            <p>
              Your settings and the rules live on the device. Every decision is made in the moment,
              from the screen in front of you.
            </p>
            <p>
              There is no sign-up, no crash reporter, no analytics and no ads.{" "}
              <strong>Nothing is collected, so there is nothing to delete.</strong>
            </p>
            <p>
              The app asks Android for a few narrow things: to read the screen of three apps, to
              draw a brief overlay, to wake on schedule, to show a notification while you fix a
              rule, and — optionally — to stay awake in the background. It does not ask for the
              internet.
            </p>
            <p className="privacy__foot">
              No contacts, no location, no storage, no camera, no microphone, no advertising ID.
              The app cannot open a network connection, so there is nothing to leak.
            </p>
          </div>

          <div className="privacy__ledger" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <dl className="ledger">
              <div className="ledger__row ledger__row--absent">
                <dt className="ledger__k">
                  Internet <span className="badge badge--amber">not requested</span>
                </dt>
                <dd className="ledger__v">
                  No uploads, no sync, no remote settings, no usage ping. The app has no network
                  permission at all.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">
                  Reading the screen <span className="badge">filter</span>
                </dt>
                <dd className="ledger__v">
                  Looks at the view while Instagram, YouTube or TikTok is open, only to recognise a
                  reel or a Short. Nothing is recorded or kept.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">
                  Which app is open <span className="badge">bedtime</span>
                </dt>
                <dd className="ledger__v">
                  Sees the name of the app in front so Block mode and the sleep window can work.
                  It reads nothing inside them.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">Draw over other apps</dt>
                <dd className="ledger__v">
                  Shows the short “blocked” flash. It cannot stack, cannot linger and has no
                  dismiss button.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">Exact alarms</dt>
                <dd className="ledger__v">
                  Bedtime and wake time fire on time, even while the phone sits in your pocket.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">Notifications</dt>
                <dd className="ledger__v">
                  Progress and “Stop now” while you fix a rule. Say no and the fix still works.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">Battery exemption</dt>
                <dd className="ledger__v">
                  Optional, asked once. Keeps an aggressive power saver from freezing the filter
                  mid-evening.
                </dd>
              </div>
              <div className="ledger__row">
                <dt className="ledger__k">Three visible apps</dt>
                <dd className="ledger__v">
                  Instagram, YouTube and TikTok — used only to bring the app to the front while you
                  capture a screen.
                </dd>
              </div>
            </dl>
          </div>
        </div>
      </div>
    </section>
  );
}
