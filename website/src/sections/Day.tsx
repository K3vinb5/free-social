/**
 * 02 / A day with Free Social — the shared budget as it actually feels.
 * One allowance across Reels, Shorts and TikTok; it resets at midnight.
 */
export function Day() {
  return (
    <section className="section" id="how" aria-labelledby="how-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">02 / A day with it</p>
          <h2 id="how-title">
            Twenty minutes. <em>Then the loop ends.</em>
          </h2>
          <p className="sec-note">
            One allowance covers Instagram Reels, YouTube Shorts and TikTok together. It resets at
            midnight, and it is yours to spend however you like — slowly, or all at once.
          </p>
        </header>

        <div className="how">
          <figure className="how__shot" data-reveal>
            <div className="phone">
              <img
                src="/assets/budget.png"
                width={720}
                height={1600}
                loading="lazy"
                decoding="async"
                alt="The budget screen: a ring showing 13 of 20 minutes left today, with the daily allowance stepper underneath."
              />
            </div>
            <figcaption className="shot-cap">
              One number for all three apps, visible whenever you open Free Social.
            </figcaption>
          </figure>

          <ol className="steps" data-reveal>
            <li>
              <span className="step__n">01</span>
              <h3>Morning: a fresh twenty.</h3>
              <p>
                The counter resets at midnight, so the day starts whole. Twenty minutes is the
                default; you can make it shorter or longer.
              </p>
            </li>
            <li>
              <span className="step__n">02</span>
              <h3>During the day: it just plays.</h3>
              <p>
                Open a reel, a Short or TikTok and it plays normally. Free Social keeps count
                quietly in the background, and the home screen shows how many minutes are left.
                While the budget lasts, nothing interrupts you.
              </p>
            </li>
            <li>
              <span className="step__n">03</span>
              <h3>The last few minutes.</h3>
              <p>
                The ring empties, and that is the only warning you get. There is no countdown, no
                nagging banner and no “are you sure?”.
              </p>
            </li>
            <li>
              <span className="step__n">04</span>
              <h3>Budget gone: “Filtering on”.</h3>
              <p>
                The next reel or Short closes with a brief flash and you are back where you were.
                The home screen says “Filtering on” until the budget resets at midnight.
              </p>
            </li>
            <li>
              <span className="step__n">05</span>
              <h3>Night: short video is off.</h3>
              <p>
                During your sleep window the budget counts as spent, so reels and Shorts stop even
                if minutes are left. Any app set to Block closes entirely; the others stay usable
                but quiet.
              </p>
            </li>
          </ol>
        </div>
      </div>
    </section>
  );
}
