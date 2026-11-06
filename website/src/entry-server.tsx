import { renderToString } from "react-dom/server";
import { App } from "./App";

/** Build-time entry: the whole page as an HTML string, hydrated later in the browser. */
export function render() {
  return renderToString(<App />);
}
