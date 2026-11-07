import type { CSSProperties } from "react";
/**
 * 02 / Behaviour — six cards: engagement clock, shared budget, per-app modes,
 * nightly lock, on-device maintenance and the rule inspector.
 */
export function Behaviour() {
  return (
    <section className="section" id="features" aria-labelledby="features-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">02 / Behaviour</p>
          <h2 id="features-title">
            A filter that knows the difference between <em>browsing</em> and <em>watching</em>.
          </h2>
          <p className="sec-note">
            Six behaviours, one idea: the app never decides what you should see. It decides when it
            is allowed to interrupt.
          </p>
        </header>

        <div className="features">
          <article className="card card--7" data-reveal>
            <p className="card__kicker">Direct engagement</p>
            <h3>The clock only starts when you do.</h3>
            <p>
              Scrolling past a reel is not a decision to watch one. Free Social classifies the
              surface, not your intent: feed cells and shelf cards are left completely alone. The
              moment Reels, Shorts or TikTok playback opens, a stopwatch runs — and stops the second
              you leave.
            </p>
            <div
              className="trace"
              role="img"
              aria-label="Behaviour trace: fourteen reels scrolled past in the feed at zero seconds with the clock idle; the Reels tab opened at 41 seconds with the clock running; playback left at 4 minutes 12 with the clock stopped."
            >
              <div className="trace__row">
                <span className="trace__t">00:00</span>
                <span>14 reels scrolled past in the feed</span>
                <span className="trace__s">idle</span>
              </div>
              <div className="trace__row is-live">
                <span className="trace__t">00:41</span>
                <span>Opened the Reels tab</span>
                <span className="trace__s">clock running</span>
              </div>
              <div className="trace__row">
                <span className="trace__t">04:12</span>
                <span>Left playback</span>
                <span className="trace__s">stopped</span>
              </div>
            </div>
          </article>

          <article className="card card--5" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <p className="card__kicker">Shared budget</p>
            <h3>Twenty minutes. Three apps. One pot.</h3>
            <p>
              A single daily allowance across all short video, so switching apps is not a loophole.
              When it is gone, playback exits behind a calm flash — <em>“Free Social blocked this”</em> — never
              a wall, a countdown or a lecture.
            </p>
            <div className="ringwrap" role="img" aria-label="Budget ring: 13 of 20 minutes left today.">
              <div className="ring">
                <svg viewBox="0 0 120 120" width={120} height={120} aria-hidden="true" focusable="false">
                  <circle
                    cx="60"
                    cy="60"
                    r="52"
                    fill="none"
                    stroke="rgba(236,234,242,0.1)"
                    strokeWidth={7}
                  />
                  <circle
                    cx="60"
                    cy="60"
                    r="52"
                    fill="none"
                    stroke="#FFB25C"
                    strokeWidth={7}
                    strokeLinecap="round"
                    strokeDasharray="212 115"
                  />
                </svg>
                <div className="ring__center">
                  <span className="ring__num">13</span>
                  <span className="ring__unit">min left</span>
                </div>
              </div>
              <p className="ring__legend">
                <b>Resets at midnight</b>During locked schedule hours the budget counts as already
                spent.
              </p>
            </div>
          </article>

          <article className="card card--5" data-reveal>
            <p className="card__kicker">Per app</p>
            <div className="card__split">
              <div className="card__txt">
                <h3>Filter, Block, or Allow.</h3>
                <p>
                  Filter hides short-video surfaces and leaves the app usable. Block closes the
                  whole app during the hours you set. Allow leaves it untouched — useful when the
                  problem is one habit, not all of them.
                </p>
              </div>
              <div className="phone mini">
                <img
                  src="/assets/app-detail.png"
                  width={820}
                  height={1645}
                  loading="lazy"
                  decoding="async"
                  alt="Instagram settings in Free Social, showing the Filter / Block / Allow selector and toggles for hiding reel cells and the Reels tab."
                />
              </div>
            </div>
          </article>

          <article className="card card--7" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <p className="card__kicker">Nightly lock</p>
            <div className="card__split">
              <div className="card__txt">
                <h3>A bedtime that is not a suggestion.</h3>
                <p>
                  Set bedtime and wake time for each weekday — one window per day, at least six
                  hours long, and always on. During locked hours the app is closed rather than
                  merely quiet — and the budget cannot be spent.
                </p>
                <p>
                  Real exact alarms fire on each boundary, so the lock flips while the phone is idle
                  in your pocket.
                </p>
              </div>
              <div className="phone mini">
                <img
                  src="/assets/schedule.png"
                  width={720}
                  height={1600}
                  loading="lazy"
                  decoding="async"
                  alt="Schedule screen: per-weekday bedtime and wake times from Monday to Sunday, each with its window length and the six-hour minimum."
                />
              </div>
            </div>
          </article>

          <article className="card" data-reveal>
            <p className="card__kicker">On-device maintenance</p>
            <h3>Broken rule? Rebuild it on the phone.</h3>
            <p>
              When a screen changes, guided node capture walks you to the right view and dumps the
              live hierarchy. Patch the rule override in-app, save it, and the running service picks
              it up immediately — no restart, no cable, no second device.
            </p>
            <div className="pillrow" aria-label="Capture, patch, live">
              <span className="pill">
                <i aria-hidden="true">→</i>01 capture
              </span>
              <span className="pill">
                <i aria-hidden="true">→</i>02 patch
              </span>
              <span className="pill">
                <i aria-hidden="true">→</i>03 live
              </span>
            </div>
          </article>

          <article className="card" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <p className="card__kicker">Rule inspector</p>
            <h3>Eight rules, all of them readable.</h3>
            <p>
              The whole catalog is a JSON asset — six enabled rules, two retired ones kept as
              evidence. The inspector shows the node tree, view-ids and bounds behind every match,
              and exports what it saw.
            </p>
            <pre className="code" aria-label="Excerpt of the shipped YouTube Shorts rule">
              <span className="c">{"// rules.json — yt_shorts_player"}</span>
              {"\n{\n  "}
              <span className="k">"id"</span>
              {": "}
              <span className="s">"yt_shorts_player"</span>
              {`,
  `}
              <span className="k">"appPackage"</span>
              {": "}
              <span className="s">"com.google.android.youtube"</span>
              {`,
  `}
              <span className="k">"surface"</span>
              {": "}
              <span className="s">"shorts_player"</span>
              {`,
  `}
              <span className="k">"match"</span>
              {`: {
    `}
              <span className="k">"viewId"</span>
              {": "}
              <span className="s">"com.google.android.youtube:id/reel_recycler"</span>
              {`
  },
  `}
              <span className="k">"action"</span>
              {": "}
              <span className="s">"BACK"</span>
              {`
}`}
            </pre>
          </article>
        </div>
      </div>
    </section>
  );
}
