import {defineConfig} from "vite";
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
        // outDir: "backend/src/universal/static"
        // (default == "./dist")
    },
    plugins: [
        ...(xr ? [basicSsl()] : []),
        tailwindcss(),
        scalaJSPlugin({projectID: 'viewer'}),
    ]
});
