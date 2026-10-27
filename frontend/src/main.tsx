import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { StorageGate } from "./pages/StorageLocation";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <StorageGate>
      <App />
    </StorageGate>
  </StrictMode>,
);
