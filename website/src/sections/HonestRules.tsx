import type { CSSProperties } from "react";

/** 03 / Honest rules — anchors, fail-open behaviour and the self-service rule fix. */
export function HonestRules() {
  return (
    <section className="section" id="rules" aria-labelledby="rules-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">03 / Honest rules</p>
          <h2 id="rules-title">
            Built on <em>honest</em> rules.
          </h2>
          <p className="sec-note">
            A blocker that finds Reels by looking for the word “Reels” works in exactly one language
            and breaks on the first rename. This one matches structure — and shows you the working.
          </p>
        </header>

        <p className="rules-lead" data-reveal>
          Free Social finds short video by structure, not by wording. Each rule is one small claim
          about the screen: <em>this container, with this id, in this app</em>. So a phone set to
          Spanish filters like one set to English — and when a rule stops being true, the app says
          so instead of guessing.
        </p>

        <div className="rules-grid">
          <article className="panel panel--7" data-reveal>
            <h3>How does it recognise a reel?</h3>
            <p>
              By an <strong>anchor</strong> — a landmark in the app's view tree that stays put. Three
              kinds count: a <strong>resource id</strong> like <code>…:id/clips_viewer_view_pager</code>, a <strong>class name</strong> like <code>androidx.viewpager2…</code>, or the <strong>bounds</strong> of a container that fills the screen. Those are the same in every language
              build, because the app has to keep them for its own code to run.
            </p>
            <p>
              Visible text is a different thing. “Reels”, “Shorts”, “Para ti”: all of it is
              translated, renamed and sometimes absent. Text is allowed as a <strong>hint</strong> —
              it can narrow a match — but it is never the proof on its own. The shipped catalog is
              graded that way:
            </p>
            <div className="anchors">
              <div>
                <code>view-id="…:id/clips_viewer_view_pager"</code>
                <span className="tag yes">proof</span>
              </div>
              <div>
                <code>classNameRegex="androidx.viewpager2…"</code>
                <span className="tag yes">proof</span>
              </div>
              <div>
                <code>bounds · full-screen container</code>
                <span className="tag yes">proof</span>
              </div>
              <div>
                <code>contentDescription="Reels"</code>
                <span className="tag">optional hint</span>
              </div>
              <div>
                <code>visible text</code>
                <span className="tag no">never the only proof</span>
              </div>
            </div>
            <p>
              Six of the eight shipped rules are enabled; the other two are retired evidence, kept
              disabled on purpose. The build fails if an enabled rule has no locale-independent
              anchor, or if that anchor is not cited against a recorded XML dump.
            </p>
            <p>
              <strong>One concrete example.</strong> YouTube's Shorts shelf has no view-id at all —
              it is a plain container inside the home feed. That rule matches the container's shape
              and treats the word “Shorts” as a hint, never as proof. Tests pin it against captured
              trees from English, German and Portuguese builds, and the catalog records it as the
              exception it is.
            </p>
          </article>

          <article
            className="panel panel--5 panel--stack"
            data-reveal
            style={{ "--d": "80ms" } as CSSProperties}
          >
            <h3>What happens when Instagram updates?</h3>
            <p>
              The rules <strong>fail open</strong> — on purpose. In plain words: if a rule stops
              matching, the filter stops filtering and lets that screen through. It does not block
              the wrong thing, and it does not pretend everything is fine. A reel that leaks is
              annoying; an app that closes your DMs is worse.
            </p>
            <p>
              So Free Social keeps a stamp: the app version each rule set was verified against. It
              compares that with the version installed, and when the app is newer, you get a warning
              with exactly one action on it — capture the new screen and re-verify. No silent
              failure, no invented certainty.
            </p>
            <div className="panel__banner">
              <div
                className="warn"
                role="img"
                aria-label="Stale-rule warning: Instagram 455.0.1.2 is newer than the rules' verified version 454.0.0.54, so filtering may be partial until re-verified. The action is Capture now."
              >
                <p className="warn__head">
                  <span className="warn__dot" aria-hidden="true"></span>Stale rules
                </p>
                <p className="warn__t">
                  Instagram 455.0.1.2 is newer than the rules' verified version 454.0.0.54
                </p>
                <p className="warn__s">
                  Filtering may be partial until re-verified. <span className="warn__btn">Capture now</span>
                </p>
              </div>
            </div>
          </article>

          <article className="panel panel--12" data-reveal>
            <h3>How do I fix a rule myself?</h3>
            <div className="rulefix">
              <div className="rulefix__txt">
                <p>
                  A rule is never written from a PC, a cable or a scrape of the store listing. It is
                  written from the screen itself, on the phone that has the app installed — and that
                  is the same path you take when an update breaks one.
                </p>
                <ol className="born">
                  <li>
                    <span>01</span>Open the screen you want recognised in the target app.
                  </li>
                  <li>
                    <span>02</span>Arm guided capture. Progress and “Stop now” live in a
                    notification; the dump records view-ids, text, bounds and classes.
                  </li>
                  <li>
                    <span>03</span>Save it as a rule override, editable in-app. The running service
                    re-reads it on the spot.
                  </li>
                </ol>
                <p>
                  The result is maintenance, not magic: no restart, no rebuild, no second device.
                  The override beats the built-in rule until you switch back to the built-in set,
                  which is also what a proper fix in the next release looks like.
                </p>
              </div>
              <figure className="rulefix__shot">
                <div className="phone mini">
                  <img
                    src="/assets/inspector.png"
                    width={720}
                    height={1600}
                    loading="lazy"
                    decoding="async"
                    alt="Rule inspector: per-app tabs, captured screens with node counts for the Instagram home feed, reels viewer, YouTube Shorts player and TikTok playback."
                  />
                </div>
                <figcaption className="shot-cap">
                  What a capture leaves behind: the screen, its node count, and the rules that
                  matched it.
                </figcaption>
              </figure>
            </div>
          </article>
        </div>
      </div>
    </section>
  );
}
