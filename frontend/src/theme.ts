import { useEffect, useState } from "react";
import { shell } from "./shell";

export type ThemeMode = "system" | "light" | "dark";

const THEME_KEY = "jobapply:theme";
const ORDER: ThemeMode[] = ["system", "light", "dark"];

export function readTheme(): ThemeMode {
  try {
    const v = window.localStorage.getItem(THEME_KEY);
    if (v === "light" || v === "dark" || v === "system") {
      return v;
    }
  } catch {
    // 忽略本地存储异常
  }
  return "system";
}

export function applyTheme(mode: ThemeMode): void {
  const root = document.documentElement;
  if (mode === "system") {
    root.removeAttribute("data-theme");
  } else {
    root.dataset.theme = mode;
  }
  shell.setTheme(mode);
}

export function initTheme(): ThemeMode {
  const mode = readTheme();
  applyTheme(mode);
  return mode;
}

export function nextTheme(mode: ThemeMode): ThemeMode {
  return ORDER[(ORDER.indexOf(mode) + 1) % ORDER.length];
}

export function themeLabel(mode: ThemeMode): string {
  switch (mode) {
    case "light":
      return "浅色模式";
    case "dark":
      return "深色模式";
    default:
      return "跟随系统";
  }
}

export function useTheme() {
  const [theme, setThemeState] = useState<ThemeMode>(readTheme);

  useEffect(() => {
    applyTheme(theme);
    const onStorage = (e: StorageEvent) => {
      if (e.key === THEME_KEY) {
        const next = readTheme();
        setThemeState(next);
        applyTheme(next);
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, [theme]);

  function setTheme(mode: ThemeMode) {
    setThemeState(mode);
    applyTheme(mode);
    try {
      window.localStorage.setItem(THEME_KEY, mode);
    } catch {
      // 忽略本地存储异常
    }
  }

  return {
    theme,
    setTheme,
    cycleTheme: () => setTheme(nextTheme(theme)),
  };
}
