import { StarIcon } from "./icons";

/** Hero: copy, CTAs and the three-phone stage. Values here are hand-maintained with the release. */
export function Hero() {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="shell hero__grid">
        <div className="hero__copy">
          <p className="eyebrow">
            <span className="dot" aria-hidden="true"></span>Android app · free & open source · no
            internet permission
          </p>
          <h1 id="hero-title">
            <span className="line">Scroll past.</span>
            <span className="line">
              Watch <em>on purpose</em>.
            </span>
          </h1>
          <p className="hero__sub">
            Free Social takes the endless short-video part out of Instagram, YouTube and TikTok —
            inside the real apps you already use. Reels and Shorts stop ambushing you. When you do
            want one, you spend it from one daily budget.
          </p>
          <div className="hero__actions">
            <a className="btn" href="downloads/free-social-0.4.1-1e7b5a1.apk" download>
              Download the APK
              <span className="btn__meta">23.7 MB · free</span>
            </a>
            <a className="btn btn--ghost" href="https://github.com/K3vinb5/free-social">
              <StarIcon size={15} />
              Star on GitHub
            </a>
            <a className="link-arrow" href="#features">
              See what it changes <span aria-hidden="true">→</span>
            </a>
          </div>
          <ul className="specs">
            <li>
              <b>Android 12L+</b>
              <span>no root · no custom ROM</span>
            </li>
            <li>
              <b>One budget</b>
              <span>3 apps · resets at midnight</span>
            </li>
            <li>
              <b>Fully offline</b>
              <span>no account · no internet</span>
            </li>
          </ul>
        </div>

        <div className="hero__visual">
          <div className="stage">
            <figure className="phone phone--l">
              <img
                src="/assets/app-detail.png"
                width={820}
                height={1645}
                loading="lazy"
                decoding="async"
                alt="Free Social's Instagram screen: Filter selected, with the choice to hide the Reels tab."
              />
            </figure>
            <figure className="phone phone--main">
              <img
                src="/assets/hub.png"
                width={820}
                height={1645}
                decoding="async"
                alt="Free Social home screen: the three apps, 20 minutes of Reels and Shorts budget left, schedule and permissions."
              />
            </figure>
            <figure className="phone phone--r">
              <img
                src="/assets/budget.png"
                width={720}
                height={1600}
                loading="lazy"
                decoding="async"
                alt="The budget screen: a ring showing 13 of 20 minutes left today, with the daily allowance stepper."
              />
            </figure>
            <p className="stage__tag">
              <span className="dot" aria-hidden="true"></span>no internet permission — it cannot
              phone home
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
