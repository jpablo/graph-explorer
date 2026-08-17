import { spawn } from "node:child_process";
import { existsSync, statSync } from "node:fs";
import { resolve } from "node:path";

// Local replacement for `@scala-js/vite-plugin-scalajs`, which cannot work
// under sbt 2.
//
// Both the upstream plugin and this file's first version recovered the linker's
// output directory by SCRAPING sbt's stdout. Upstream took the last line;
// that broke under sbt 2, whose thin client writes its logs to stdout and ends
// with a bare `ESC[0J`, so the "answer" was the escape and vite died with
// `Could not load [0J/main.js`. The fix was to take the last line that IS an
// existing directory — immune to ordering, but still assuming the value reaches
// stdout at all.
//
// It does not always. `viewer` uses dynver + BuildInfo, and dynver's version
// carries a MINUTE-resolution timestamp: an sbt invocation on the far side of a
// minute from the previous one sees a changed version, regenerates BuildInfo and
// recompiles a source. With that extra log traffic in the stream, the task
// result line goes missing and the build fails with "sbt printed no existing
// directory" after transforming 0 modules. CI passed at :43 and failed at :54 on
// an identical tree; Netlify hit the same thing. It reads as random because the
// trigger is a clock boundary.
//
// So stop parsing stdout. `viewer/scalaJSOutputDirFile` writes the path to a
// file and we read the file. sbt's console output is not an API; a file is.

import { readFileSync } from "node:fs";

/**
 * Run an sbt task and read the path it wrote.
 *
 * The task is still what BUILDS the linker output, so this call cannot be
 * skipped — see the plugin comment below.
 */
function sbtOutputDir(task, outputFile, cwd) {
  const args = ["--batch", "-no-colors", "-Dsbt.supershell=false", task];
  // stderr stays inherited so compile errors reach the terminal unfiltered.
  const options = { cwd, stdio: ["ignore", "inherit", "inherit"] };
  const child =
    process.platform === "win32"
      ? spawn("sbt.bat", args.map((x) => `"${x}"`), { shell: true, ...options })
      : spawn("sbt", args, options);

  return new Promise((resolve, reject) => {
    child.on("error", (err) => {
      reject(new Error(`Could not start sbt for Scala.js resolution. Is it installed?\n${err}`));
    });
    child.on("close", (code) => {
      if (code !== 0) {
        reject(new Error(`sbt exited with code ${code} while running \`${task}\`.`));
        return;
      }
      let directory;
      try {
        directory = readFileSync(outputFile, "utf-8").trim();
      } catch (err) {
        reject(new Error(`\`${task}\` did not write ${outputFile}: ${err.message}`));
        return;
      }
      // Assert rather than trust: a stale file from an older layout would
      // otherwise resolve imports against a directory that no longer holds the
      // current build.
      if (!directory || !existsSync(directory) || !statSync(directory).isDirectory()) {
        reject(
          new Error(
            `\`${task}\` wrote "${directory}" to ${outputFile}, which is not an existing directory.`
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
 * Keeping the sbt call (rather than reading the file alone) is deliberate: the
 * task BUILDS the linker output as a side effect. Dropping it would make
 * `npm run build` silently bundle whatever stale output happened to be on disk
 * — the same class of failure as a desktop binary embedding a stale `dist/`,
 * and just as silent.
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
      const task = isDev ? "scalaJSFastOutputDirFile" : "scalaJSOutputDirFile";
      const outputFile = resolve(
        cwd ?? process.cwd(),
        "target",
        isDev ? "scalajs-fast-output-dir.txt" : "scalajs-output-dir.txt"
      );
      scalaJSOutputDir = await sbtOutputDir(
        projectID ? `${projectID}/${task}` : task,
        outputFile,
        cwd
      );
    },

    resolveId(source) {
      if (scalaJSOutputDir === undefined) throw new Error("buildStart must run before resolveId");
      if (!source.startsWith(fullURIPrefix)) return null;
      return `${scalaJSOutputDir}/${source.substring(fullURIPrefix.length)}`;
    },
  };
}
