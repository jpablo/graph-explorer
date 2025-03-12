import {defineConfig} from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import path from "path";

export default defineConfig({
    // base: "/abc",
    server: {
        https: false
    },
    root: '.',
    publicDir: 'viewer/src/main/resources',
    resolve: {
        alias: {
            // '@viz-js/viz': path.resolve(__dirname, 'node_modules', '.vite', 'deps', '@viz-js_viz.js')
        }
    },
    build: {
        sourcemap: true,
        // outDir: "backend/src/universal/static"
        // (default == "./dist")
    },
    plugins: [scalaJSPlugin({projectID: 'viewer'})]
});
