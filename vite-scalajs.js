import { spawn } from "node:child_process";
import { existsSync, statSync } from "node:fs";

// Local replacement for `@scala-js/vite-plugin-scalajs`, which cannot work
// under sbt 2.
//
// The upstream plugin asks sbt where the Scala.js output went
// (`print viewer/fullLinkJSOutput`) and takes the LAST line of sbt's stdout as
// the answer. That held under sbt 1 for a reason that was never stated: sbt 1
// wrote `[info]`/`[success]` to stderr, which the plugin does not capture, so
// the task value was the only thing on stdout. sbt 2's thin client writes its
// logs to stdout too, and ends the run with a bare `ESC[0J`. The last line is
// then that escape, so the output directory resolves to the string "[0J" and
// vite dies with `Could not load [0J/main.js`. `-no-colors`, `NO_COLOR`,
// `SBT_NATIVE_CLIENT=false` and `sbt.log.noformat` do not suppress it.
//
// So do not trust the position of the value in the stream. Strip ANSI, then
// take the last line that is an existing directory. That is immune to log
// lines, to their ordering, and to whatever sbt decides to print next.

const ANSI = /\x1B\[[0-?]*[ -/]*[@-~]/g;

/**
 * Ask sbt for a task's value, and pick it out of the output by what it IS (a
 * directory that exists) rather than by where it sits in the stream.
 */
function printSbtTask(task, cwd) {
  const args = ["--batch", "-no-colors", "-Dsbt.supershell=false", `print ${task}`];
  // stderr stays inherited so compile errors reach the terminal unfiltered,
  // exactly as the upstream plugin did.
  const options = { cwd, stdio: ["ignore", "pipe", "inherit"] };
  const child =
    process.platform === "win32"
      ? spawn("sbt.bat", args.map((x) => `"${x}"`), { shell: true, ...options })
      : spawn("sbt", args, options);

  let output = "";
  child.stdout.setEncoding("utf-8");
  child.stdout.on("data", (data) => {
    output += data;
    // Tee, so `sbt` compiling Scala.js is visible during a vite build instead
    // of looking like a hang.
    process.stdout.write(data);
  });

  return new Promise((resolve, reject) => {
    child.on("error", (err) => {
      reject(new Error(`Could not start sbt for Scala.js resolution. Is it installed?\n${err}`));
    });
    child.on("close", (code) => {
      if (code !== 0) {
        reject(new Error(`sbt exited with code ${code} while resolving \`${task}\`.`));
        return;
      }

      const directory = output
        .replace(ANSI, "")
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line !== "" && existsSync(line) && statSync(line).isDirectory())
        .at(-1);

      if (directory === undefined) {
        reject(
          new Error(
            `sbt printed no existing directory for \`${task}\`, so the Scala.js ` +
              `output could not be located. Raw output follows:\n` +
              output.replace(ANSI, "").trimEnd()
          )
        );
        return;
      }
      resolve(directory);
    });
  });
}

/**
 * Resolve `scalajs:<name>` imports to the linker's output directory.
 *
 * Keeping the sbt call (rather than deriving the path ourselves) is
 * deliberate: `print <project>/fullLinkJSOutput` BUILDS the output as a side
 * effect. Dropping it would make `npm run build` silently bundle whatever
 * stale linker output happened to be on disk — the same class of failure as a
 * desktop binary embedding a stale `dist/`, and a silent one.
 */
export default function scalaJSPlugin(options = {}) {
  const { cwd, projectID, uriPrefix } = options;
  const fullURIPrefix = uriPrefix ? uriPrefix + ":" : "scalajs:";
  let isDev = undefined;
  let scalaJSOutputDir = undefined;

  return {
    name: "scalajs:local-sbt-resolver",

    configResolved(resolvedConfig) {
      isDev = resolvedConfig.mode === "development";
    },

    async buildStart() {
      if (isDev === undefined) throw new Error("configResolved must run before buildStart");
      const task = isDev ? "fastLinkJSOutput" : "fullLinkJSOutput";
      scalaJSOutputDir = await printSbtTask(projectID ? `${projectID}/${task}` : task, cwd);
    },

    resolveId(source) {
      if (scalaJSOutputDir === undefined) throw new Error("buildStart must run before resolveId");
      if (!source.startsWith(fullURIPrefix)) return null;
      return `${scalaJSOutputDir}/${source.substring(fullURIPrefix.length)}`;
    },
  };
}
