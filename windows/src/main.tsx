import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createHashRouter, RouterProvider } from "react-router-dom";
import App from "./App";
import "./styles/reset.css";
import "./styles/tokens.css";
import "./styles/globals.css";
import "./styles/components.css";

const router = createHashRouter([
  {
    path: "*",
    element: <App />,
  },
]);

const root = document.getElementById("root");

if (!root) {
  throw new Error("DeskCubby root element is missing");
}

createRoot(root).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
