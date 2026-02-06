import { writable } from "svelte/store";
import { getThemePreference, persistThemePreference, type ThemeMode } from "$lib/services/theme";

export const themeMode = writable<ThemeMode>("system");

function applyTheme(theme: ThemeMode): void {
  const root = document.documentElement;
  if (theme === "system") {
    root.setAttribute("data-theme", "_auto_");
    return;
  }
  root.setAttribute("data-theme", theme);
}

function readLocalTheme(): ThemeMode | null {
  const saved = window.localStorage.getItem("preferred_theme");
  if (saved === "light" || saved === "dark" || saved === "system") {
    return saved;
  }
  return null;
}

async function loadServerTheme(): Promise<ThemeMode> {
  const preference = await getThemePreference();
  if (preference.theme === "light" || preference.theme === "dark") {
    return preference.theme;
  }
  return "system";
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
    themeMode.set("system");
    applyTheme("system");
  }
}

export async function updateTheme(nextTheme: ThemeMode): Promise<void> {
  themeMode.set(nextTheme);
  applyTheme(nextTheme);
  window.localStorage.setItem("preferred_theme", nextTheme);

  try {
    await persistThemePreference(nextTheme === "system" ? null : nextTheme, nextTheme === "system");
  } catch (error) {
    console.error("Failed to persist theme preference", error);
  }
}
