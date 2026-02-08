import { mount } from "svelte";
import App from "./App.svelte";
import "$styles/global.css";
import { getRouteManifest } from "$lib/services/pages";
import { setRouteManifest } from "$lib/router/router";
import { RouteManifestSchema } from "$lib/validation/schemas";

async function initializeRouteManifest(): Promise<void> {
  if (typeof window === "undefined") {
    return;
  }
  const embeddedManifest = RouteManifestSchema.safeParse(window.__FMB_ROUTE_MANIFEST__);
  if (embeddedManifest.success) {
    setRouteManifest(embeddedManifest.data);
    return;
  }
  try {
    const manifest = await getRouteManifest();
    setRouteManifest(manifest);
  } catch (error) {
    console.warn("[bootstrap] Failed to initialize route manifest from /api/pages/routes", error);
  }
}

await initializeRouteManifest();

const app = mount(App, {
  target: document.getElementById("app")!,
});

export default app;
