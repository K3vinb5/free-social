import type { CSSProperties } from "react";

/**
 * 03 / Honest rules — why it keeps working across languages, what happens when an
 * app updates, and how the same screen you are looking at can fix a broken rule.
 */
export function HonestRules() {
  return (
    <section className="section" id="rules" aria-labelledby="rules-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">03 / Honest rules</p>
          <h2 id="rules-title">
            Rules that tell you when they <em>stop working</em>.
          </h2>
          <p className="sec-note">
            Apps change. A filter that pretends otherwise is lying to you. Free Social looks for
            the structure of a screen rather than the word “Reels”, and when an update breaks that,
            it says so — and helps you fix it from the phone.
          </p>
        </header>

        <p className="rules-lead" data-reveal>
          Free Social recognises short video by the shape of the screen: a container, its
          identifier, the way it fills the display. Those landmarks are the same whether the phone
          is in English, Portuguese or German, because the app needs them for its own code. The
          word “Reels” is only ever a hint, never the proof.
        </p>

        <div className="rules-grid">
          <article className="panel panel--7" data-reveal>
            <h3>What happens when Instagram updates?</h3>
            <p>
              A rule can stop matching. In plain words: if Free Social no longer recognises a
              screen, it <strong>lets the screen through</strong> instead of blocking the wrong
              thing. It never guesses. A reel that slips past is annoying; an app that closes your
              DMs is worse.
            </p>
            <p>
              The app also knows which version of each app its rules were checked against. When the
              installed app is newer, you get a warning with one button on it — re-check the screen.
              No silent failure, and no pretending everything is fine.
            </p>
            <p>
              It sounds unglamorous because it is. Honest is a feature: a filter that tells you when
              it is unsure is worth more than one that swears it is always right.
            </p>
          </article>

          <article
            className="panel panel--5 panel--stack"
            data-reveal
            style={{ "--d": "80ms" } as CSSProperties}
          >
            <h3>You will see this before you notice a leak.</h3>
            <p>
              A short, specific warning — not a red screen. It names the app that changed and gives
              you the only useful action: check the new screen.
            </p>
            <div className="panel__banner">
              <div
                className="warn"
                role="img"
                aria-label="Warning: Instagram was updated after these rules were checked. Some reels may get through until you re-check the screen. The action is Capture now."
              >
                <p className="warn__head">
                  <span className="warn__dot" aria-hidden="true"></span>Rules may be out of date
                </p>
                <p className="warn__t">Instagram was updated after these rules were checked.</p>
                <p className="warn__s">
                  Some reels may get through until you re-check.{" "}
                  <span className="warn__btn">Capture now</span>
                </p>
              </div>
            </div>
          </article>

          <article className="panel panel--12" data-reveal>
            <h3>Fix it yourself, from the phone.</h3>
            <div className="rulefix">
              <div className="rulefix__txt">
                <p>
                  There is no PC, no cable and no waiting for a new release. Open the app that
                  changed, find the screen that matters and let Free Social look at it. The whole
                  thing usually takes about a minute.
                </p>
                <ol className="born">
                  <li>
                    <span>01</span>Open the screen you want recognised in Instagram, YouTube or
                    TikTok.
                  </li>
                  <li>
                    <span>02</span>Start a capture. Progress and “Stop now” stay in a notification
                    while it records what is on screen.
                  </li>
                  <li>
                    <span>03</span>Save it. The filter picks up the new rule immediately — no
                    restart, no reinstall, no second device.
                  </li>
                </ol>
                <p>
                  The fix stays in place until you switch back to the built-in rules. When the next
                  release ships a proper fix, you can go back to it with one tap.
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
                    alt="The rule screen: captured screens for Instagram, YouTube and TikTok, with the rules that matched each one."
                  />
                </div>
                <figcaption className="shot-cap">
                  What a capture leaves behind: the screen and the rules that recognise it.
                </figcaption>
              </figure>
            </div>
          </article>
        </div>
      </div>
    </section>
  );
}
