import { mount } from "svelte";
import App from "./App.svelte";
import "$styles/global.css";
import { initializeRouteManifest } from "$lib/router/router";

await initializeRouteManifest();

const app = mount(App, {
  target: document.getElementById("app")!,
});

export default app;
