import React from "react";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./theme/tokens.css";

function registerSw() {
  if ("serviceWorker" in navigator && location.protocol.startsWith("http")) {
    const controlledAtLoad = navigator.serviceWorker.controller !== null;
    let refreshing = false;
    navigator.serviceWorker.addEventListener("controllerchange", () => {
      if (controlledAtLoad && !refreshing) {
        refreshing = true;
        location.reload();
      }
    });
    window.addEventListener("load", () => {
      navigator.serviceWorker.register("/dc-web-sw.js").then((registration) => registration.update()).catch(() => undefined);
    });
  }
}

// Pointer focus is not a selection state. Blur pointer-activated buttons after
// release/click so mouse, touch, and pen cannot leave a second item looking
// selected. Keyboard clicks have detail=0, keep focus, and use :focus-visible.
document.addEventListener("pointerup", (event) => {
  const target = event.target instanceof Element ? event.target.closest("button") : null;
  if (target instanceof HTMLButtonElement) target.blur();
});
document.addEventListener("click", (event) => {
  // Keyboard activation reports detail=0 and must keep its visible focus.
  if (event.detail === 0) return;
  const target = event.target instanceof Element ? event.target.closest("button") : null;
  if (target instanceof HTMLButtonElement) target.blur();
});

const container = document.getElementById("root");
const router = createBrowserRouter([{ path: "*", element: <App /> }]);
if (container) {
  createRoot(container).render(
    <React.StrictMode>
      <RouterProvider router={router} />
    </React.StrictMode>
  );
}
registerSw();
