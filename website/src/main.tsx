import { StrictMode } from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import { App } from "./App";
import { usePageBehaviour } from "./usePageBehaviour";

function Root() {
  usePageBehaviour();
  return <App />;
}

const container = document.getElementById("root");
if (!container) throw new Error("#root is missing from index.html");

const tree = (
  <StrictMode>
    <Root />
  </StrictMode>
);

// Production loads the prerendered markup and only hydrates it (the reveal
// animation is the sole client behaviour). `vite dev` serves the empty shell,
// where the placeholder is a text node, so it gets a normal client render.
if (container.firstElementChild) {
  hydrateRoot(container, tree);
} else {
  container.textContent = "";
  createRoot(container).render(tree);
}
