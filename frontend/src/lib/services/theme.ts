import { getJson, postJson } from "$lib/services/http";
import { ThemePreferenceSchema, type ThemePreference } from "$lib/validation/schemas";

export type ThemeMode = "light" | "dark" | "system";

export function getThemePreference(): Promise<ThemePreference> {
  return getJson("/api/theme", ThemePreferenceSchema, "getThemePreference");
}

export function persistThemePreference(theme: ThemeMode | null, useSystem: boolean): Promise<ThemePreference> {
  return postJson("/api/theme", { theme, useSystem }, ThemePreferenceSchema, "persistThemePreference");
}
