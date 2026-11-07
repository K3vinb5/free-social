import type { CSSProperties } from "react";

/**
 * 01 / What it does — the three apps, one budget and the per-app choice.
 * Written for someone deciding whether to install it, not for the changelog.
 */
export function WhatItDoes() {
  return (
    <section className="section" id="features" aria-labelledby="features-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">01 / What it does</p>
          <h2 id="features-title">
            The reels stop. <em>The app stays.</em>
          </h2>
          <p className="sec-note">
            Free Social works inside Instagram, YouTube and TikTok — the real apps, already signed
            in. It takes away the endless short-video part and leaves everything else where it was.
          </p>
        </header>

        <div className="features">
          <article className="card card--7" data-reveal>
            <p className="card__kicker">Instagram</p>
            <div className="card__split">
              <div className="card__txt">
                <h3>Reels stop being somewhere you fall into.</h3>
                <p>
                  When your minutes are gone, opening Reels closes it and puts you back where you
                  were. No wall, no countdown, no pop-up asking you to reconsider.
                </p>
                <p>
                  Your feed, Stories, DMs, comments and search are left alone. A reel someone sent
                  in a chat still plays, because a conversation is not a scroll.
                </p>
              </div>
              <div className="phone mini">
                <img
                  src="/assets/app-detail.png"
                  width={820}
                  height={1645}
                  loading="lazy"
                  decoding="async"
                  alt="Free Social's Instagram screen: Filter selected, with the option to hide the Reels tab."
                />
              </div>
            </div>
          </article>

          <article className="card card--5" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <p className="card__kicker">YouTube</p>
            <h3>Shorts end where they start.</h3>
            <p>
              The full-screen Shorts player closes and lands you back on the page you came from.
              Regular videos, search, subscriptions and comments are untouched.
            </p>
            <p>
              Regular YouTube is still regular YouTube. Only the full-screen Shorts player closes
              once the budget is gone; the rest of the app is left where it was.
            </p>
          </article>

          <article className="card card--5" data-reveal>
            <p className="card__kicker">TikTok</p>
            <h3>The For You loop stops looping.</h3>
            <p>
              Full-screen TikTok playback ends the same way: a brief flash, then back to where you
              were. Messages, search and profiles stay exactly as they are.
            </p>
            <p>
              The habit is the feed, not the app. Free Social removes the loop without taking the
              app away.
            </p>
          </article>

          <article className="card card--7" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <p className="card__kicker">Bedtime</p>
            <div className="card__split">
              <div className="card__txt">
                <h3>A quiet window that is not a suggestion.</h3>
                <p>
                  Set a bedtime and a wake time for each day. During those hours the budget counts
                  as spent, so reels and Shorts stop even if minutes are left — and any app you set
                  to <strong>Block</strong> closes entirely. Every day needs at least six hours of
                  sleep in the schedule, and there is no off switch.
                </p>
                <p>
                  It is the one part of the app that is strict on purpose. You set it once, while
                  thinking clearly, and it keeps the promise for you at 23:40.
                </p>
              </div>
              <div className="phone mini">
                <img
                  src="/assets/schedule.png"
                  width={720}
                  height={1600}
                  loading="lazy"
                  decoding="async"
                  alt="Schedule screen: bedtime and wake time for each day, set to 22:30–07:00 by default, each row showing its window length against the six-hour minimum."
                />
              </div>
            </div>
          </article>

          <article className="card card--7" data-reveal>
            <p className="card__kicker">Filter, Block or Allow</p>
            <h3>One choice per app.</h3>
            <p>
              <strong>Filter</strong> handles short video only. <strong>Block</strong> closes the
              whole app every time it opens. <strong>Allow</strong> leaves an app completely alone
              — useful when the problem is one app, not all three.
            </p>
            <p>
              Nothing is decided for you. Free Social does not curate your feed or judge what you
              should see; it only ends the next reel or Short once your time is gone.
            </p>
          </article>

          <article className="card card--5" data-reveal style={{ "--d": "80ms" } as CSSProperties}>
            <p className="card__kicker">How it feels</p>
            <h3>No lectures.</h3>
            <p>
              When Free Social steps in, it shows a short flash — “Free Social blocked this” — and
              gets out of the way. No moralising, no streak to protect, no screen-time graph to
              feel guilty about.
            </p>
            <div className="pillrow" aria-label="Calm, brief, out of the way">
              <span className="pill">
                <i aria-hidden="true">→</i>brief
              </span>
              <span className="pill">
                <i aria-hidden="true">→</i>quiet
              </span>
              <span className="pill">
                <i aria-hidden="true">→</i>done
              </span>
            </div>
          </article>
        </div>
      </div>
    </section>
  );
}
