/** 01 / Setup — the three-step install, with the restricted-settings screenshot. */
export function Setup() {
  return (
    <section className="section" id="how" aria-labelledby="how-title">
      <div className="shell">
        <header className="sec-head" data-reveal>
          <p className="sec-index">01 / Setup</p>
          <h2 id="how-title">
            Three steps, about <em>four minutes</em>.
          </h2>
          <p className="sec-note">
            No account, no sign-in, no wizard that phones home. Installing is the slow part — after
            that the app explains every permission it asks for, in plain words.
          </p>
        </header>

        <div className="how">
          <figure className="how__shot" data-reveal>
            <div className="phone">
              <img
                src="/assets/restricted-settings.png"
                width={820}
                height={1645}
                loading="lazy"
                decoding="async"
                alt="Setup card in the app: 'Allow restricted settings', with the three steps — open app info, tap the menu, allow restricted settings — and a button to open app settings."
              />
            </div>
            <figcaption className="shot-cap">
              The setup card that walks you through Android 13's restriction on sideloaded
              accessibility apps.
            </figcaption>
          </figure>

          <ol className="steps" data-reveal>
            <li>
              <span className="step__n">01</span>
              <h3>Install the APK</h3>
              <p>
                The APK installs straight from the page. The .zip is the same signed build with the
                README inside, for when you want the install steps offline. Android 12L or newer.
              </p>
            </li>
            <li>
              <span className="step__n">02</span>
              <h3>Enable the accessibility service</h3>
              <p>
                Android 13 and later hides accessibility behind an extra switch for sideloaded
                apps. In app info, open the menu and choose <code>Allow restricted settings</code>,
                then switch on the content filter service. The optional block service is separate.
              </p>
            </li>
            <li>
              <span className="step__n">03</span>
              <h3>Set your budget</h3>
              <p>
                Twenty minutes a day by default — one allowance shared by Instagram, YouTube and
                TikTok, reset at midnight. Per app you can also choose Filter, Block or Allow.
              </p>
            </li>
          </ol>
        </div>
      </div>
    </section>
  );
}
