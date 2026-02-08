import { defineConfig, type ProxyOptions } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import tailwindcss from "@tailwindcss/vite";
import { svelteTesting } from "@testing-library/svelte/vite";
import { resolve } from "node:path";

const ROOT_DIR = resolve(__dirname);
const OUTPUT_DIR = resolve(ROOT_DIR, "../src/main/resources/static/frontend");

function proxyConfig(target: string, websocket = false): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    ws: websocket,
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [svelte(), tailwindcss(), svelteTesting()],
  base: mode === "development" ? "/" : "/frontend/",
  build: {
    outDir: OUTPUT_DIR,
    emptyOutDir: true,
    sourcemap: true,
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        entryFileNames: "app.js",
        chunkFileNames: "chunks/[name]-[hash].js",
        assetFileNames: (assetInfo) =>
          assetInfo.name?.endsWith(".css") ? "app.css" : "assets/[name]-[hash][extname]",
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": proxyConfig("http://localhost:8095"),
      "/ws": proxyConfig("http://localhost:8095", true),
      "/topic": proxyConfig("http://localhost:8095", true),
      "/sitemap.xml": proxyConfig("http://localhost:8095"),
      "/sitemap-xml": proxyConfig("http://localhost:8095"),
      "/r": proxyConfig("http://localhost:8095"),
    },
  },
  resolve: {
    alias: {
      $lib: resolve(ROOT_DIR, "src/lib"),
      $styles: resolve(ROOT_DIR, "src/styles"),
      $test: resolve(ROOT_DIR, "src/test"),
    },
  },
  test: {
    globals: true,
    environment: "happy-dom",
    include: ["src/**/*.{test,spec}.ts"],
    setupFiles: ["src/test/setup.ts"],
  },
}));
