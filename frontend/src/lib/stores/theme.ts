import { writable, get } from "svelte/store";
import { getThemePreference, persistThemePreference } from "$lib/services/theme";

/** The resolved theme is always concrete — never "system". */
export type ResolvedTheme = "light" | "dark";

export const themeMode = writable<ResolvedTheme>("light");

function detectSystemTheme(): ResolvedTheme {
  if (typeof window === "undefined") {
    return "light";
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyTheme(theme: ResolvedTheme): void {
  document.documentElement.setAttribute("data-theme", theme);
}

function readLocalTheme(): ResolvedTheme | null {
  const saved = window.localStorage.getItem("preferred_theme");
  if (saved === "light" || saved === "dark") {
    return saved;
  }
  if (saved === "system") {
    return detectSystemTheme();
  }
  return null;
}

async function loadServerTheme(): Promise<ResolvedTheme> {
  const preference = await getThemePreference();
  if (preference.theme === "light" || preference.theme === "dark") {
    return preference.theme;
  }
  return detectSystemTheme();
}

export async function initializeTheme(): Promise<void> {
  if (typeof window === "undefined") {
    return;
  }

  const localTheme = readLocalTheme();
  if (localTheme) {
    themeMode.set(localTheme);
    applyTheme(localTheme);
    return;
  }

  try {
    const serverTheme = await loadServerTheme();
    themeMode.set(serverTheme);
    applyTheme(serverTheme);
    window.localStorage.setItem("preferred_theme", serverTheme);
  } catch (error) {
    console.error("Failed to load theme preference", error);
    const fallback = detectSystemTheme();
    themeMode.set(fallback);
    applyTheme(fallback);
  }
}

export async function toggleTheme(): Promise<void> {
  const current = get(themeMode);
  const next: ResolvedTheme = current === "light" ? "dark" : "light";

  themeMode.set(next);
  applyTheme(next);
  window.localStorage.setItem("preferred_theme", next);

  try {
    await persistThemePreference(next, false);
  } catch (error) {
    console.error("Failed to persist theme preference", error);
  }
}
