import {defineConfig} from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

export default defineConfig({
    // base: "/abc",
    server: {
        https: false
    },
    root: '.',
    publicDir: 'viewer/src/main/resources',
    build: {
        sourcemap: true,
        // outDir: "backend/src/universal/static"
        // (default == "./dist")
    },
    plugins: [scalaJSPlugin({projectID: 'viewer'})]
});
