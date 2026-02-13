import { mount } from "svelte";
import App from "./App.svelte";
import "$styles/global.css";
import { initializeRouteManifest } from "$lib/router/router";

await initializeRouteManifest();

const target = document.getElementById("app");
if (!target) {
  throw new Error("Missing #app element");
}

const app = mount(App, {
  target,
});

export default app;
