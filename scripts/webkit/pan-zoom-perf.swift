// Measures the pan and the zoom of the canvas in the system WebKit, the engine
// of the macOS desktop window. See scripts/webkit-pan-zoom-perf.sh.
//
// The program opens a WebKit window on the vite dev server and opens a diagram
// from a DOT file that vite serves. Then the page sends one wheel event in each
// animation frame, as a trackpad does, first for a pan and then for a zoom
// (a wheel event with the Command key). For each gesture it prints:
//   - the script time of one wheel event (the handlers of the app)
//   - the script time of the animation-frame callbacks of the app, per frame
//   - the time between frames: median, 95th percentile, and maximum
//   - the number of frames that took more than 1.5 frame periods
//   - the time of the DOM calls that can force a layout, per frame
//
// STUB_CTM=1 is an experiment, not a check: getScreenCTM gives back a matrix
// from a cache. The difference shows the cost of the forced layouts.
//
// A frame time includes the script, the style, the layout, and the paint. A
// display at 60 Hz has a frame period of 16.7 ms, and a display at 120 Hz has
// a frame period of 8.3 ms.
//
// The desktop app turns off the 60 fps limit of WebKit (desktop/src-tauri/src/
// frame_rate.rs), so the program does the same. CAP_60FPS=1 keeps the limit on,
// to compare with the default of WebKit.
//
// Usage: pan-zoom-perf <app url> <dot path on the server> [events]
import AppKit
import WebKit

// Print each line at once: the wrapper stops a run that takes too long, and a
// buffered line would go away with it.
setvbuf(stdout, nil, _IONBF, 0)

let args = CommandLine.arguments
guard args.count >= 3, let appURL = URL(string: args[1]) else {
  print("usage: pan-zoom-perf <app url> <dot path on the server> [events]")
  exit(2)
}
let dotPath = args[2]
let events = args.count > 3 ? Int(args[3]) ?? 240 : 240

let app = NSApplication.shared
app.setActivationPolicy(.regular)
// The program is not the front app, so after some seconds macOS App Nap slows
// it: the page then gets about 2.5 frames each second, also with no events.
// The desktop app is the front app while a person uses it. This activity
// keeps App Nap off, so that the numbers show the page and not App Nap.
let activity = ProcessInfo.processInfo.beginActivity(
  options: [.userInitiated, .latencyCritical],
  reason: "pan and zoom measurement"
)
let config = WKWebViewConfiguration()
config.websiteDataStore = .nonPersistent()
// The same switch as the desktop app: WebKit keeps the page near 60 fps by
// default, also on a 120 Hz display. The feature list and the switch are
// private WebKit API, so the program checks each selector first.
if ProcessInfo.processInfo.environment["CAP_60FPS"] != "1" {
  let prefs = config.preferences
  let listSel = NSSelectorFromString("_features")
  let setSel = NSSelectorFromString("_setEnabled:forFeature:")
  var off = false
  if (WKPreferences.self as AnyObject).responds(to: listSel), prefs.responds(to: setSel),
    let features = (WKPreferences.self as AnyObject).perform(listSel)?.takeUnretainedValue() as? [NSObject]
  {
    for f in features where (f.value(forKey: "key") as? String) == "PreferPageRenderingUpdatesNear60FPSEnabled" {
      typealias SetEnabled = @convention(c) (AnyObject, Selector, Bool, AnyObject) -> Void
      unsafeBitCast(prefs.method(for: setSel), to: SetEnabled.self)(prefs, setSel, false, f)
      off = true
    }
  }
  print(off ? "the 60 fps limit is off, as in the desktop app" : "this WebKit has no 60 fps limit to turn off")
}
let window = NSWindow(
  contentRect: NSRect(x: 80, y: 80, width: 1400, height: 900),
  styleMask: [.titled, .closable],
  backing: .buffered,
  defer: false
)
let web = WKWebView(frame: window.contentView!.bounds, configuration: config)
web.autoresizingMask = [.width, .height]
window.contentView!.addSubview(web)
// WebKit stops the frames of a window that macOS reports as covered. Keep the
// window above the others, and turn off that detection where the switch exists.
window.level = .floating
let occlusionSwitch = NSSelectorFromString("_setWindowOcclusionDetectionEnabled:")
if web.responds(to: occlusionSwitch) {
  web.perform(occlusionSwitch, with: false)
}
window.makeKeyAndOrderFront(nil)
window.orderFrontRegardless()
web.load(URLRequest(url: appURL))

func fail(_ message: String) -> Never {
  print("FAIL: \(message)")
  exit(2)
}

func sleep(_ seconds: Double) async {
  try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
}

func run(_ body: String) async -> Any? {
  do {
    return try await web.callAsyncJavaScript(body, arguments: [:], in: nil, contentWorld: .page)
  } catch {
    fail("script error: \(error)")
  }
}

/// Opens the diagram, and waits until the canvas shows its nodes.
let openDiagram = """
  for (let i = 0; i < 100 && !document.body; i++) await new Promise(r => setTimeout(r, 100));
  const text = await fetch(\(String(reflecting: dotPath))).then(r => r.text());
  history.pushState(null, '', '/diagrams/pan-zoom-perf?dot=' + encodeURIComponent(text));
  dispatchEvent(new PopStateEvent('popstate'));
  // The app read the text. A short URL again: the browser sends the URL of the
  // page as the Referer of each later request, for example the module of
  // Mermaid, and vite refuses a header that is too long (HTTP 431).
  history.replaceState(null, '', '/diagrams/pan-zoom-perf');
  for (let i = 0; i < 900; i++) {
    const n = document.querySelectorAll('#canvas-container svg g.node').length;
    if (n > 0) { await new Promise(r => setTimeout(r, 1500)); return n; }
    await new Promise(r => setTimeout(r, 100));
  }
  return 0;
  """

/// Sends `events` wheel events, one in each frame, and gives back the numbers.
func gesture(_ kind: String) -> String {
  """
  const kind = \(String(reflecting: kind)), n = \(events);
  const svg = document.querySelector('#canvas-container svg');
  const box = svg.getBoundingClientRect();
  const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
  const frames = [];
  // Time the animation-frame callbacks of the app: the pan writes the
  // transform in such a callback, not in the wheel handler.
  const rawRaf = window.requestAnimationFrame;
  let rafTime = 0;
  window.requestAnimationFrame = cb => rawRaf.call(window, t => {
    const t0 = performance.now(); cb(t); rafTime += performance.now() - t0;
  });
  // Time the DOM calls that can force a style or a layout pass.
  const timed = {};
  const restore = [];
  const wrap = (proto, name, label) => {
    const raw = proto[name];
    if (!raw) return;
    timed[label] = 0;
    proto[name] = function (...a) {
      const t0 = performance.now();
      try { return raw.apply(this, a); } finally { timed[label] += performance.now() - t0; }
    };
    restore.push(() => { proto[name] = raw; });
  };
  wrap(Element.prototype, 'querySelectorAll', 'querySelectorAll');
  wrap(Element.prototype, 'setAttribute', 'setAttribute');
  // STUB_CTM=1 (an experiment, not a check): give back a matrix from the
  // cache, to show the cost of the forced layout. The app draws some controls
  // in the wrong place while the stub is on.
  if (\(ProcessInfo.processInfo.environment["STUB_CTM"] == "1" ? "true" : "false")) {
    const raw = SVGGraphicsElement.prototype.getScreenCTM, cache = new WeakMap();
    SVGGraphicsElement.prototype.getScreenCTM = function () {
      if (!cache.has(this)) cache.set(this, raw.call(this));
      return cache.get(this);
    };
    restore.push(() => { SVGGraphicsElement.prototype.getScreenCTM = raw; });
  }
  wrap(SVGGraphicsElement.prototype, 'getScreenCTM', 'getScreenCTM');
  wrap(SVGGraphicsElement.prototype, 'getBBox', 'getBBox');
  wrap(Element.prototype, 'getBoundingClientRect', 'getBoundingClientRect');
  wrap(window, 'getComputedStyle', 'getComputedStyle');
  let last = await new Promise(r => rawRaf.call(window, r));
  let script = 0;
  for (let i = 0; i < n; i++) {
    const out = (i % 60) < 30;
    const init = kind === 'zoom'
      ? { deltaY: out ? -3 : 3, clientX: cx, clientY: cy, metaKey: true }
      : { deltaX: out ? 5 : -5, deltaY: out ? 3 : -3, clientX: cx, clientY: cy };
    const ev = new WheelEvent('wheel', { ...init, bubbles: true, cancelable: true });
    const t0 = performance.now();
    svg.dispatchEvent(ev);
    script += performance.now() - t0;
    const t = await new Promise(r => rawRaf.call(window, r));
    frames.push(t - last);
    last = t;
  }
  await new Promise(r => setTimeout(r, 300));
  window.requestAnimationFrame = rawRaf;
  restore.forEach(f => f());
  const domMsPerFrame = Object.fromEntries(Object.entries(timed).map(([k, v]) => [k, +(v / n).toFixed(3)]));
  const s = frames.slice(5).sort((a, b) => a - b);
  const q = p => s[Math.min(s.length - 1, Math.floor(s.length * p))];
  const period = s[Math.floor(s.length * 0.1)];
  return JSON.stringify({
    gesture: kind,
    events: n,
    handlerMsPerEvent: +(script / n).toFixed(3),
    rafMsPerFrame: +(rafTime / n).toFixed(3),
    frameMedianMs: +q(0.5).toFixed(1),
    frameP95Ms: +q(0.95).toFixed(1),
    frameMaxMs: +s[s.length - 1].toFixed(1),
    longFrames: s.filter(f => f > period * 1.5).length,
    elements: svg.querySelectorAll('*').length,
    domMsPerFrame
  });
  """
}

/// 60 frames with no events, and some facts about the page: a slow frame
/// here comes from the page itself, not from the gesture.
let idle = """
  const frames = [];
  let last = await new Promise(r => requestAnimationFrame(r));
  for (let i = 0; i < 60; i++) {
    const t = await new Promise(r => requestAnimationFrame(r));
    frames.push(t - last);
    last = t;
  }
  const s = frames.sort((a, b) => a - b);
  const g = document.querySelector('#canvas-container svg g.gx-canvas-root');
  return JSON.stringify({
    idle: true,
    frameMedianMs: +s[s.length >> 1].toFixed(1),
    frameMaxMs: +s[s.length - 1].toFixed(1),
    svgs: document.querySelectorAll('#canvas-container svg').length,
    transitioning: !!document.querySelector('.gx-transitioning'),
    transform: g && g.getAttribute('transform'),
    animations: document.getAnimations ? document.getAnimations().length : -1
  });
  """

Task { @MainActor in
  await sleep(1)
  guard let nodes = await run(openDiagram) as? Int, nodes > 0 else { fail("the diagram did not open") }
  print("nodes: \(nodes)")
  // A warm-up pass, so that the first measured frames do not include the JIT.
  _ = await run(gesture("pan").replacingOccurrences(of: "n = \(events)", with: "n = 30"))
  for kind in ["pan", "zoom", "pan", "zoom"] {
    if let json = await run(gesture(kind)) as? String { print(json) }
    if let json = await run(idle) as? String { print(json) }
  }
  exit(0)
}
app.run()
