.PHONY: setup dev-compile dev-serve test build \
        desktop-build desktop-run desktop-status desktop-watch

DESKTOP_BIN  := desktop/src-tauri/target/release/graph-explorer-desktop
GX           := gx/target/release/gx
CONTROL_FILE := $(HOME)/.graph-explorer/runtime/control.json

setup:
	npm install

dev-compile:
	sbt "~viewer/fastLinkJS"

dev-serve:
	npm run dev

test:
	sbt testFull

build:
	sbt "viewer/fullLinkJS"
	npm run build

# --- desktop app (graph-explorer-desktop + gx) ---------------------------
#
# These shell out to the script rather than inlining the steps: the build
# cannot run from inside sbt (vite resolves scalajs: imports by shelling back
# into sbt, which deadlocks against the task waiting for it), and the script is
# also what keeps the local build in step with release-binaries.yml.

desktop-build:
	./scripts/build-local-capabilities-release.sh

# Build, then run it in the foreground so stderr is visible and Ctrl-C quits.
# Any already-running instance is stopped first: two desktops both write
# ~/.graph-explorer/runtime/control.json, so the second silently steals `gx`
# from the first and you end up driving a window you are not looking at.
#
# The pid comes from the control file, not from `pkill -f`: this runs inside a
# shell whose own command line contains the pattern, so a -f match can kill the
# recipe's shell instead of the app.
desktop-run: desktop-build
	@pid=$$(sed -n 's/.*"pid"[^0-9]*\([0-9][0-9]*\).*/\1/p' "$(CONTROL_FILE)" 2>/dev/null); \
	if [ -n "$$pid" ] && kill -0 "$$pid" 2>/dev/null; then \
		echo "stopping the running desktop instance (pid $$pid)"; \
		kill "$$pid"; \
	fi
	$(DESKTOP_BIN)

desktop-status:
	@test -x $(GX) || { echo "gx is not built yet — run: make desktop-build" >&2; exit 2; }
	@$(GX) status --json

# make desktop-watch FILE=path/to/diagram.dot
desktop-watch:
	@test -n "$(FILE)" || { echo "usage: make desktop-watch FILE=path/to/diagram.dot" >&2; exit 2; }
	@test -x $(GX) || { echo "gx is not built yet — run: make desktop-build" >&2; exit 2; }
	@$(GX) watch "$(FILE)" --json
