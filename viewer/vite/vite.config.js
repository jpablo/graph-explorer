import {defineConfig} from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import path from "path";

export default defineConfig({
    // base: "/abc",
    server: {
        https: false
    },
    root: '.',
    publicDir: '../src/main/resources',
    build: {
        sourcemap: true,
        // outDir: "backend/src/universal/static"
        // (default == "./dist")
    },
    // TODO: figure out how to configure Vite to avoid these.
    resolve: {
        alias: {
            '@codemirror/commands': path.resolve(__dirname, 'node_modules/@codemirror/commands'),
            '@codemirror/view': path.resolve(__dirname, 'node_modules/@codemirror/view'),
            '@viz-js/lang-dot': path.resolve(__dirname, 'node_modules/@viz-js/lang-dot'),
            '@viz-js/viz': path.resolve(__dirname, 'node_modules/@viz-js/viz'),
            'codemirror': path.resolve(__dirname, 'node_modules/codemirror'),
            'dot-parser': path.resolve(__dirname, 'node_modules/dot-parser'),
            'uuid': path.resolve(__dirname, 'node_modules/uuid')
        }
    },
    plugins: [
        scalaJSPlugin({projectID: 'viewer', cwd: '../..'})
    ]
});
