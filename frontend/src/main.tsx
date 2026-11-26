import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { StorageGate } from "./pages/StorageLocation";
import { back } from "./route";
import { shell } from "./shell";
import { initTheme } from "./theme";
import "./styles.css";

initTheme();
if (shell.available()) {
  document.documentElement.classList.add("macapp");
  shell.onBack(() => void back());
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <StorageGate>
      <App />
    </StorageGate>
  </StrictMode>,
);
