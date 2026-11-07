/** 05 / Limits — the four edges you actually meet. */
export function Limits() {
  return (
    <section className="section" id="limits" aria-labelledby="limits-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">05 / Limits</p>
          <h2 id="limits-title">
            What this <em>doesn't</em> do.
          </h2>
          <p className="sec-note">
            Every tool has edges. These are the ones you will actually meet — stated before you
            install, not after.
          </p>
        </header>

        <ul className="limits" data-reveal>
          <li>
            <div className="limit__h">
              <span className="limit__n">01</span>
              <h3>App updates can loosen it</h3>
            </div>
            <p>
              When Instagram, YouTube or TikTok changes how a screen is built, a rule can stop
              matching and a reel can get through. The app tells you, and fixing it takes about a
              minute on the phone — not a new download. It is maintenance, and calling it magic
              would be a lie.
            </p>
          </li>
          <li>
            <div className="limit__h">
              <span className="limit__n">02</span>
              <h3>Your phone can switch it off</h3>
            </div>
            <p>
              Aggressive battery savers freeze background apps within minutes of the screen going
              off. Free Social asks once to be exempt and shows a banner when the filter is not
              running. It cannot overrule your phone, and it will not pretend to.
            </p>
          </li>
          <li>
            <div className="limit__h">
              <span className="limit__n">03</span>
              <h3>You install it yourself</h3>
            </div>
            <p>
              There is no Play Store listing. You install an APK and grant a permission that reads
              as alarming — because it is powerful. You give it to three apps, and you can take it
              back in Settings whenever you like.
            </p>
          </li>
          <li>
            <div className="limit__h">
              <span className="limit__n">04</span>
              <h3>It handles short video, not the feed</h3>
            </div>
            <p>
              Your feed, DMs, comments and search are untouched, on purpose. This is not a
              screen-time tracker, a DNS blocker or a parental control. It is one specific
              intervention, done carefully.
            </p>
          </li>
        </ul>
      </div>
    </section>
  );
}
