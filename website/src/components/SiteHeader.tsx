import { StarIcon } from "./icons";

/** Fixed header: brand, section links, the "Star on GitHub" pill and the download CTA. */
export function SiteHeader() {
  return (
    <header className="site-header">
      <div className="shell">
        <nav className="nav" aria-label="Primary">
          <a className="brand" href="#top">
            <img src="/assets/mark.svg" alt="" width={20} height={20} />
            <span>Free Social</span>
          </a>
          <ul className="nav__links">
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
              <a href="#privacy">Privacy</a>
            </li>
          </ul>
          <a
            className="nav__gh"
            href="https://github.com/K3vinb5/free-social"
            aria-label="Star Free Social on GitHub"
          >
            <StarIcon size={14} />
            <span>Star on GitHub</span>
          </a>
          <a className="nav__cta" href="#download">
            Download
          </a>
        </nav>
      </div>
    </header>
  );
}
