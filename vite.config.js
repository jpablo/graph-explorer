import {spawnSync} from "child_process";
import {defineConfig} from "vite";


function isDev() {
    return process.env.NODE_ENV !== "production";
}

function printSbtTask(task) {
    const args = ["--error", "--batch", `print ${task}`];
    const options = {
        stdio: [
            "pipe", // StdIn.
            "pipe", // StdOut.
            "inherit", // StdErr.
        ],
    };
    const result = process.platform === 'win32'
        ? spawnSync("sbt.bat", args.map(x => `"${x}"`), {shell: true, ...options})
        : spawnSync("sbt", args, options);

    if (result.error)
        throw result.error;
    if (result.status !== 0)
        throw new Error(`sbt process failed with exit code ${result.status}`);
    let str = result.stdout.toString('utf8');
    return str.substring(0, str.indexOf('\n')).trim();
}

const replacementForPublic = isDev()
    ? printSbtTask("publicDev")
    : printSbtTask("publicProd");

export default defineConfig({
    // base: "/abc",
    root: '.',
    publicDir: 'viewer/src/main/resources',
    build: {
        sourcemap: true,
        // outDir: "backend/src/universal/static"
        // (default == "./dist")
    },
    resolve: {
        alias: [
            {
                find: "@public",
                replacement: replacementForPublic,
            },
        ],
    }
});
