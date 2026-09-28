import {defineConfig} from "vite";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
// Local resolver, not `@scala-js/vite-plugin-scalajs`: the upstream plugin
// reads the LAST line of sbt's stdout as the linker output directory, which
// sbt 2 broke by moving its logs onto stdout. See vite-scalajs.js.
import scalaJSPlugin from "./vite-scalajs.js";
import tailwindcss from "@tailwindcss/vite";
import basicSsl from "@vitejs/plugin-basic-ssl";

// `npm run dev:xr` sets GX_XR=1: HTTPS (WebXR requires a secure context) and
// LAN exposure so a headset can reach this machine. Kept OUT of plain
// `npm run dev` on purpose — https://localhost is a DIFFERENT localStorage
// origin than http://localhost, so switching the daily server would "hide"
// the library.
const xr = !!process.env.GX_XR;

// Not `import.meta.dirname`: CI builds the frontend on Node 18, which lacks it.
const root = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
    // base: "/abc",
    server: {
        watch: {
            ignored: ['**/.claude-trace/**', '**/node_modules/**'],
            usePolling: false
        }
    },
    root: '.',
    publicDir: 'viewer/src/main/resources',
    build: {
        sourcemap: true,
        // Two pages. `index.html` is the app, for the web and the desktop.
        // `site/index.html` is the product page, which Netlify serves at `/`
        // (see viewer/src/main/resources/_redirects). The desktop never loads
        // it. Keep the product page out of `app.html` and `app/index.html`:
        // Tauri resolves the `/app` route to those files before `index.html`.
        rollupOptions: {
            input: {
                // The key names the chunk: scripts/build-local-capabilities-release.sh
                // looks for dist/assets/index-*.js.
                index: resolve(root, "index.html"),
                site: resolve(root, "site/index.html"),
            },
        },
        // outDir: "backend/src/universal/static"
        // (default == "./dist")
    },
    plugins: [
        ...(xr ? [basicSsl()] : []),
        tailwindcss(),
        scalaJSPlugin({projectID: 'viewer'}),
    ]
});
