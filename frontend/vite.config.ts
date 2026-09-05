import { defineConfig } from "vite";
import solid from "vite-plugin-solid";

/**
 * How the browser bundle is built, and why each setting is what it is.
 *
 * The output of `vite build` is copied verbatim into the `web/` directory the gateway serves from
 * its own classpath (`frontend.bundle` in `build.mill`). There is no second server, no proxy and no
 * CORS: the assets and the API share an origin in development exactly as they do in production,
 * which is ADR-012 Amendment 1 and the reason a Vite dev server with an `/api` proxy is not used
 * here — a CORS configuration that exists only in development is a configuration that only breaks
 * in development.
 */
export default defineConfig({
  plugins: [solid()],

  // Every asset URL in the built `index.html` is written relative to the document, so it resolves
  // against the `<base href="<basePath>/ui/">` the gateway injects. An absolute `/assets/…` would
  // hard-code the mount point into the build and break every deployment behind a reverse proxy.
  base: "./",

  build: {
    outDir: "dist",
    emptyOutDir: true,

    // `dist/.vite/manifest.json` is not a convenience here, it is a build check. It states for the
    // entry chunk which modules are static `imports` and which are `dynamicImports`, which is how
    // SOL-010 proves that a feature's code is not in the entry chunk — reading the module graph
    // rather than grepping the chunk text, because the entry legitimately contains a feature's name
    // (its route pattern and its import specifier) and a text search is wrong in both directions.
    manifest: true,

    // A browser that fails to parse the bundle shows a blank page and says very little, so the
    // target is stated rather than inherited: these are the browsers KUI supports.
    target: ["chrome111", "edge111", "firefox111", "safari16.4"],

    sourcemap: true,

    rolldownOptions: {
      output: {
        // Chunks are named after the workspace package they came from, not after the file that
        // happens to be their entry — every package's entry is `src/index.tsx`, so the default
        // naming produces a bundle full of chunks called `src-<hash>.js`.
        //
        // This is not cosmetic. E2E-001 asserts that a cluster with no topic capability never
        // downloads the topics feature, and it does that by watching the network for a request
        // whose name identifies the feature (DEVPLAN exit criterion 5). A chunk called `src` is
        // unassertable, and four of them are indistinguishable.
        chunkFileNames(chunk) {
          const from = chunk.facadeModuleId ?? "";
          const packageName = /packages\/([^/]+)\//.exec(from)?.[1];
          return packageName === undefined
            ? "assets/[name]-[hash].js"
            : `assets/${packageName}-[hash].js`;
        },
      },
    },

    // Nothing is fetched from a third party at runtime — this product runs in private networks —
    // so fonts and other assets are emitted as files next to the code rather than inlined past a
    // size where the entry chunk starts to carry them (exit criterion 13).
    assetsInlineLimit: 4096,
  },

  // The development loop is `vite build --watch`, not `vite dev`: the gateway is the only server,
  // and it serves whatever is currently in `dist/`. See `./mill devWatch`.
  server: {
    // If somebody does start a Vite dev server anyway, fail loudly on a port clash rather than
    // silently moving to another port and serving a stale gateway page from the expected one.
    strictPort: true,
  },
});
