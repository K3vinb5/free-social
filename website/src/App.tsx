import { Hero } from "./components/Hero";
import { SiteFooter } from "./components/SiteFooter";
import { SiteHeader } from "./components/SiteHeader";
import { Behaviour } from "./sections/Behaviour";
import { Download } from "./sections/Download";
import { HonestRules } from "./sections/HonestRules";
import { Limits } from "./sections/Limits";
import { Privacy } from "./sections/Privacy";
import { Setup } from "./sections/Setup";

/**
 * The whole page. Rendered to a string at build time (src/entry-server.tsx) into
 * index.html, then hydrated in the browser for the reveal animation only.
 */
export function App() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <Hero />
        <Setup />
        <Behaviour />
        <HonestRules />
        <Privacy />
        <Limits />
        <Download />
      </main>
      <SiteFooter />
    </>
  );
}
