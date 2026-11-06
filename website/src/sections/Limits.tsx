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
              <h3>Rules drift after app updates</h3>
            </div>
            <p>
              An app update renames a view and the rule stops matching: the reel shows, and the app
              tells you why instead of pretending. Fixing it takes a node capture, not a new release
              — but it is maintenance, and calling it magic would be a lie.
            </p>
          </li>
          <li>
            <div className="limit__h">
              <span className="limit__n">02</span>
              <h3>Battery savers kill accessibility services</h3>
            </div>
            <p>
              Some Android builds freeze background services within minutes of the screen going off.
              Free Social asks for a battery exemption once, then shows a persistent banner when
              enforcement is degraded. It cannot force the issue, and it will not claim to.
            </p>
          </li>
          <li>
            <div className="limit__h">
              <span className="limit__n">03</span>
              <h3>Sideload only</h3>
            </div>
            <p>
              No Play Store listing. You install an APK and grant a permission that reads as
              alarming — because it is powerful. You give it to three apps, and you can revoke it in
              Settings whenever you like.
            </p>
          </li>
          <li>
            <div className="limit__h">
              <span className="limit__n">04</span>
              <h3>It filters short video, not the feed</h3>
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
