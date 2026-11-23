// Checks that a toolbar dropdown panel is DRAWN ON THE SCREEN after a click, in
// the system WebKit (the engine of Safari and of the macOS desktop window).
//
// The bug: in WebKit 27 the panel of a toolbar dropdown opens in the DOM. It
// has display: flex and opacity: 1, it is the top element at its position, and
// it takes clicks. But after the 0.2 s open animation, the screen does not show
// it. The DOM state is correct, so only the pixels on the screen show the bug.
//
// Two things do not see the bug:
//   - Playwright's WebKit, which is older than WebKit 27.
//   - WKWebView.takeSnapshot, which draws the page again in its own pass and
//     shows the panel. So this check captures the real window with
//     `screencapture`, which needs the Screen Recording permission.
//
// The steps:
//   1. Open the first example of the library page, and select its first node.
//   2. Click the first dropdown trigger of #selection-toolbar (the node shape),
//      with native mouse events.
//   3. Capture the window, and sample the pixels inside the panel.
//
// The panel is drawn when at least half of the samples have the panel
// background color, within 4 for each color channel. Without the panel, the
// samples show the canvas grid or the white canvas.
//
// Usage: dropdown-paint <app url> [output dir]
//   The output dir gets screen.png, the window capture.
//   EXTRA_CSS adds a style sheet to the page, to try a CSS change without a build.
// Exit codes: 0 the panel is drawn, 1 the panel is not drawn, 2 setup error.

import AppKit
import WebKit

setvbuf(stdout, nil, _IONBF, 0)

let args = CommandLine.arguments
guard args.count > 1, let appURL = URL(string: args[1]) else {
  print("usage: dropdown-paint <app url> [output dir]")
  exit(2)
}
let outputDir = args.count > 2
  ? URL(fileURLWithPath: args[2])
  : FileManager.default.temporaryDirectory.appendingPathComponent("dropdown-paint-\(getpid())")
try? FileManager.default.createDirectory(at: outputDir, withIntermediateDirectories: true)

// A terminal program cannot make its window the active window on macOS. WebKit
// matches :focus and :focus-within only in an active window, and the dropdown
// opens on :focus-within. So the window reports that it is the key window.
final class KeyWindow: NSWindow {
  override var canBecomeKey: Bool { true }
  override var isKeyWindow: Bool { true }
}

let app = NSApplication.shared
app.setActivationPolicy(.regular)
let config = WKWebViewConfiguration()
config.websiteDataStore = .nonPersistent()
if let css = ProcessInfo.processInfo.environment["EXTRA_CSS"], !css.isEmpty {
  let source = "{ const s = document.createElement('style'); s.textContent = \(String(reflecting: css)); document.head.appendChild(s); }"
  config.userContentController.addUserScript(
    WKUserScript(source: source, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
  )
  print("EXTRA_CSS: \(css)")
}
let window = KeyWindow(
  contentRect: NSRect(x: 80, y: 80, width: 1400, height: 900),
  styleMask: [.titled, .closable],
  backing: .buffered,
  defer: false
)
let web = WKWebView(frame: window.contentView!.bounds, configuration: config)
web.autoresizingMask = [.width, .height]
window.contentView!.addSubview(web)
// WebKit stops drawing a window that macOS reports as covered by other windows,
// and then the capture shows an old frame. So keep the window above the others,
// and turn off that detection where the WebKit build has the switch for it.
window.level = .floating
let occlusionSwitch = NSSelectorFromString("_setWindowOcclusionDetectionEnabled:")
if web.responds(to: occlusionSwitch) {
  web.perform(occlusionSwitch, with: false)
}
window.makeKeyAndOrderFront(nil)
window.orderFrontRegardless()
window.makeFirstResponder(web)
web.load(URLRequest(url: appURL))

func fail(_ message: String, code: Int32 = 2) -> Never {
  print("FAIL: \(message)")
  exit(code)
}

func sleep(_ seconds: Double) async {
  try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
}

func js(_ source: String) async -> Any? {
  try? await web.evaluateJavaScript(source)
}

/// Waits until `source` gives a non-null value, for `timeout` seconds at most.
func waitFor(_ source: String, timeout: Double = 15) async -> Any? {
  var waited = 0.0
  while waited < timeout {
    if let value = await js(source), !(value is NSNull) { return value }
    await sleep(0.2)
    waited += 0.2
  }
  return nil
}

func point(_ value: Any?) -> CGPoint? {
  guard let a = value as? [Double], a.count >= 2 else { return nil }
  return CGPoint(x: a[0], y: a[1])
}

/// The center of the first element that `selector` finds, in page coordinates.
func centerScript(_ selector: String) -> String {
  "(() => { const e = document.querySelector(\(String(reflecting: selector))); if (!e) return null; " +
    "const b = e.getBoundingClientRect(); if (b.width === 0) return null; return [b.x + b.width / 2, b.y + b.height / 2]; })()"
}

func mouse(_ type: NSEvent.EventType, _ p: CGPoint) {
  let local = web.isFlipped ? p : CGPoint(x: p.x, y: web.bounds.height - p.y)
  let event = NSEvent.mouseEvent(
    with: type,
    location: web.convert(local, to: nil),
    modifierFlags: [],
    timestamp: ProcessInfo.processInfo.systemUptime,
    windowNumber: window.windowNumber,
    context: nil,
    eventNumber: 0,
    clickCount: 1,
    pressure: type == .leftMouseUp ? 0 : 1
  )!
  switch type {
  case .leftMouseDown: web.mouseDown(with: event)
  case .leftMouseUp: web.mouseUp(with: event)
  default: web.mouseMoved(with: event)
  }
}

func click(_ p: CGPoint) async {
  mouse(.mouseMoved, p)
  await sleep(0.2)
  mouse(.leftMouseDown, p)
  await sleep(0.1)
  mouse(.leftMouseUp, p)
}

/// Captures the window as the screen shows it, without its shadow.
func captureWindow() -> NSBitmapImageRep {
  let file = outputDir.appendingPathComponent("screen.png")
  try? FileManager.default.removeItem(at: file)
  let task = Process()
  task.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
  task.arguments = ["-x", "-o", "-l\(window.windowNumber)", file.path]
  do { try task.run() } catch { fail("cannot run screencapture: \(error)") }
  task.waitUntilExit()
  guard task.terminationStatus == 0, let data = try? Data(contentsOf: file), let rep = NSBitmapImageRep(data: data) else {
    fail("screencapture made no image. Give the terminal app the Screen Recording permission.")
  }
  return rep
}

typealias RGB = (r: Double, g: Double, b: Double)

func distance(_ a: RGB, _ b: RGB) -> Double {
  max(abs(a.r - b.r), abs(a.g - b.g), abs(a.b - b.b))
}

Task { @MainActor in
  let example = "[title^=\"Open this example\"]"
  guard let examplePoint = point(await waitFor(centerScript(example))) else { fail("no example on the library page") }
  await click(examplePoint)

  guard point(await waitFor(centerScript("svg g.node"))) != nil else { fail("the example shows no node") }
  // The layout can still move for a moment after the first draw.
  await sleep(2.5)

  // Select the node. A click that comes too early does not select, so try twice.
  let triggerSelector = "#selection-toolbar .dropdown > [role=button]"
  var triggerPoint: CGPoint? = nil
  for _ in 0..<2 where triggerPoint == nil {
    guard let nodePoint = point(await js(centerScript("svg g.node"))) else { fail("the example shows no node") }
    await click(nodePoint)
    triggerPoint = point(await waitFor(centerScript(triggerSelector), timeout: 4))
  }
  guard let triggerPoint else { fail("no dropdown trigger in #selection-toolbar after the node click") }
  await sleep(1)

  let panelScript = """
    (() => {
      const trigger = document.querySelector('\(triggerSelector)');
      const panel = trigger.parentElement.querySelector('.dropdown-content');
      const cs = getComputedStyle(panel);
      const r = panel.getBoundingClientRect();
      const bg = cs.backgroundColor.match(/[\\d.]+/g).map(Number);
      const bar = document.getElementById('selection-toolbar').getBoundingClientRect();
      return { display: cs.display, opacity: cs.opacity, x: r.x, y: r.y, w: r.width, h: r.height,
               bg: bg, focused: document.activeElement === trigger,
               barX: bar.x, barY: bar.y, barW: bar.width, barH: bar.height };
    })()
    """
  await click(triggerPoint)
  // The open animation takes 0.2 s. The bug shows after it ends.
  await sleep(1.2)
  guard let panel = await js(panelScript) as? [String: Any] else { fail("cannot read the panel") }
  let screen = captureWindow()

  let display = panel["display"] as? String ?? "?"
  let focused = panel["focused"] as? Bool ?? false
  let x = panel["x"] as? Double ?? 0, y = panel["y"] as? Double ?? 0
  let w = panel["w"] as? Double ?? 0, h = panel["h"] as? Double ?? 0
  let bgList = panel["bg"] as? [Double] ?? []
  print("panel: display=\(display) opacity=\(panel["opacity"] ?? "?") focused=\(focused) rect=\(Int(x)),\(Int(y)),\(Int(w)),\(Int(h)) bg=\(bgList)")
  guard focused, display != "none", w > 20, h > 20, bgList.count >= 3 else {
    fail("the panel did not open in the DOM, so this check cannot test the drawing")
  }
  let background: RGB = (bgList[0], bgList[1], bgList[2])

  // The capture holds the whole window: the title bar, then the web view.
  let scale = Double(screen.pixelsWide) / window.frame.width
  let titleBar = window.frame.height - window.contentLayoutRect.height
  func pixel(_ px: Double, _ py: Double) -> RGB? {
    guard let c = screen.colorAt(x: Int(px * scale), y: Int((py + titleBar) * scale))?.usingColorSpace(.sRGB) else { return nil }
    return (c.redComponent * 255, c.greenComponent * 255, c.blueComponent * 255)
  }

  // The capture must be current. The selection toolbar row is not part of the
  // bug, so the capture and a snapshot of the page must agree there. A snapshot
  // makes WebKit draw the page again, so it comes AFTER the capture.
  guard let image = try? await web.takeSnapshot(configuration: WKSnapshotConfiguration()),
        let tiff = image.tiffRepresentation, let snap = NSBitmapImageRep(data: tiff)
  else { fail("no snapshot of the page") }
  let snapScale = Double(snap.pixelsWide) / web.bounds.width
  let barX = panel["barX"] as? Double ?? 0, barY = panel["barY"] as? Double ?? 0
  let barW = panel["barW"] as? Double ?? 0, barH = panel["barH"] as? Double ?? 0
  var barSamples = 0, barSame = 0
  var by = barY + 4
  while by < min(barY + barH - 4, y - 2) {
    var bx = barX + 4
    while bx < barX + barW - 4 {
      if let a = pixel(bx, by),
         let c = snap.colorAt(x: Int(bx * snapScale), y: Int(by * snapScale))?.usingColorSpace(.sRGB) {
        barSamples += 1
        if distance(a, (c.redComponent * 255, c.greenComponent * 255, c.blueComponent * 255)) <= 16 { barSame += 1 }
      }
      bx += 6
    }
    by += 4
  }
  let barRatio = barSamples == 0 ? 0 : Double(barSame) / Double(barSamples)
  print("toolbar row: \(barSame) of \(barSamples) samples agree between the capture and a snapshot (\(Int(barRatio * 100))%)")
  guard barSamples >= 50, barRatio >= 0.9 else {
    fail("the window capture is not current (it does not show the selection toolbar), so this check cannot judge the panel")
  }

  // Sample the inside of the panel, away from its edges. A drawn panel has its
  // background color in most samples; only the shape icons differ.
  var samples = 0, drawn = 0
  var sy = y + 8
  while sy < y + h - 8 {
    var sx = x + 8
    while sx < x + w - 8 {
      if let p = pixel(sx, sy) {
        samples += 1
        // A small limit: the panel color (247, 248, 246) is only 9 from the
        // white canvas, so a limit of 10 counted the canvas as the panel.
        if distance(p, background) <= 4 { drawn += 1 }
      }
      sx += 6
    }
    sy += 6
  }
  guard samples >= 50 else { fail("the panel is too small to sample (\(samples) samples)") }
  let ratio = Double(drawn) / Double(samples)
  let version = Bundle(for: WKWebView.self).infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
  print("WebKit \(version): \(drawn) of \(samples) samples on the screen have the panel color (\(Int(ratio * 100))%)")
  print("window capture: \(outputDir.appendingPathComponent("screen.png").path)")
  if ratio >= 0.5 {
    print("PASS: the dropdown panel is drawn")
    exit(0)
  }
  fail("the dropdown panel is open in the DOM, but the screen does not show it", code: 1)
}

app.run()
