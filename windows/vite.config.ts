import react from "@vitejs/plugin-react";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { extname, join, relative, resolve, sep } from "node:path";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

const tauriHost = process.env.TAURI_DEV_HOST;
const PDF_ASSET_DIRECTORIES = ["cmaps", "standard_fonts", "wasm", "iccs"] as const;

function collectFiles(root: string, directory = root): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    return statSync(path).isDirectory() ? collectFiles(root, path) : [path];
  });
}

function pdfJsAssets(): Plugin {
  const packageRoot = resolve(import.meta.dirname, "node_modules/pdfjs-dist");
  return {
    name: "deskcubby-pdfjs-assets",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const pathname = request.url?.split("?", 1)[0] ?? "";
        if (!pathname.startsWith("/pdfjs-assets/")) return next();
        const parts = pathname.slice("/pdfjs-assets/".length).split("/");
        const directory = parts.shift();
        if (!directory || !PDF_ASSET_DIRECTORIES.includes(directory as typeof PDF_ASSET_DIRECTORIES[number])) {
          response.statusCode = 404;
          return response.end();
        }
        let decoded: string;
        try {
          decoded = parts.map((part) => decodeURIComponent(part)).join(sep);
        } catch {
          response.statusCode = 400;
          return response.end();
        }
        const root = resolve(packageRoot, directory);
        const target = resolve(root, decoded);
        if (!decoded || (!target.startsWith(`${root}${sep}`) && target !== root)) {
          response.statusCode = 404;
          return response.end();
        }
        try {
          const body = readFileSync(target);
          const mime = {
            ".bcmap": "application/octet-stream",
            ".bin": "application/octet-stream",
            ".js": "text/javascript; charset=utf-8",
            ".mjs": "text/javascript; charset=utf-8",
            ".pfb": "application/octet-stream",
            ".ttf": "font/ttf",
            ".wasm": "application/wasm",
          }[extname(target).toLocaleLowerCase()] ?? "application/octet-stream";
          response.setHeader("Content-Type", mime);
          response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
          return response.end(body);
        } catch {
          response.statusCode = 404;
          return response.end();
        }
      });
    },
    generateBundle() {
      for (const directory of PDF_ASSET_DIRECTORIES) {
        const root = resolve(packageRoot, directory);
        for (const sourcePath of collectFiles(root)) {
          const assetPath = relative(root, sourcePath).split(sep).join("/");
          this.emitFile({
            type: "asset",
            fileName: `pdfjs-assets/${directory}/${assetPath}`,
            source: readFileSync(sourcePath),
          });
        }
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), pdfJsAssets()],
  clearScreen: false,
  envPrefix: ["VITE_", "TAURI_"],
  server: {
    port: 1420,
    strictPort: true,
    host: tauriHost ?? "127.0.0.1",
    hmr: tauriHost
      ? {
          protocol: "ws",
          host: tauriHost,
          port: 1421,
        }
      : undefined,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
  },
  build: {
    target: "es2021",
    minify: process.env.TAURI_DEBUG ? false : "esbuild",
    sourcemap: Boolean(process.env.TAURI_DEBUG),
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/test/setup.ts",
    css: true,
    restoreMocks: true,
  },
});
