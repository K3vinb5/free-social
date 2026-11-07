import { Hero } from "./components/Hero";
import { SiteFooter } from "./components/SiteFooter";
import { SiteHeader } from "./components/SiteHeader";
import { Day } from "./sections/Day";
import { Download } from "./sections/Download";
import { HonestRules } from "./sections/HonestRules";
import { Limits } from "./sections/Limits";
import { Privacy } from "./sections/Privacy";
import { WhatItDoes } from "./sections/WhatItDoes";

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
        <WhatItDoes />
        <Day />
        <HonestRules />
        <Privacy />
        <Limits />
        <Download />
      </main>
      <SiteFooter />
    </>
  );
}
