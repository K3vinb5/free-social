/** Site footer: brand blurb, product/elsewhere columns and the legal line. */
export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="shell">
        <div className="foot">
          <div className="foot__brand-col">
            <p className="foot__brand">
              <img src="/assets/mark.svg" alt="" width={20} height={20} />
              Free Social
            </p>
            <p className="foot__tag">
              A small Android app that takes the endless part out of Instagram, YouTube and TikTok.
              Free, open source, and completely offline.
            </p>
          </div>
          <div>
            <h4>Product</h4>
            <ul>
              <li>
                <a href="#features">What it does</a>
              </li>
              <li>
                <a href="#how">A day with it</a>
              </li>
              <li>
                <a href="#rules">Honest rules</a>
              </li>
              <li>
                <a href="#download">Download</a>
              </li>
            </ul>
          </div>
          <div>
            <h4>Elsewhere</h4>
            <ul>
              <li>
                <a href="https://github.com/K3vinb5/free-social">Source on GitHub</a>
              </li>
              <li>
                <a href="#privacy">Privacy</a>
              </li>
              <li>
                <a href="#limits">Limitations</a>
              </li>
            </ul>
          </div>
          <p className="foot__legal">
            <span>
              <a href="https://github.com/K3vinb5/free-social/blob/master/LICENSE">MIT</a> © 2026
              Kevin Borges · Free Social 0.4.1
            </span>
            <span>
              No cookies, no analytics, no third-party requests. Android is a trademark of Google
              LLC.
            </span>
          </p>
        </div>
      </div>
    </footer>
  );
}
