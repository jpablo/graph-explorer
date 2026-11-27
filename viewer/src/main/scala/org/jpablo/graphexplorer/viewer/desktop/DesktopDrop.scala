package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.utils.ClientPoint
import org.scalajs.dom

import scala.scalajs.js

/** A drop of files from the desktop on the canvas.
  *
  * Tauri takes the drags of files for the window (`dragDropEnabled`, true by default). On macOS, the web view then
  * gets no DOM `dragenter`, `dragover` or `drop` event, also not for text or a link. Tauri sends its own events with
  * the paths of the files on the disk, and the place of the pointer:
  *
  *   - `tauri://drag-enter` `{ paths, position }`
  *   - `tauri://drag-over` `{ position }`
  *   - `tauri://drag-drop` `{ paths, position }`
  *   - `tauri://drag-leave`
  *
  * The place is from the top-left corner of the web view. Tauri calls it a `PhysicalPosition`, but the unit comes from
  * wry, and it is different for each platform. On macOS, wry gives the `draggingLocation` of AppKit, which is in
  * points: the CSS pixels of the page. On Linux, GTK also gives logical pixels. On Windows, WebView2 gives physical
  * pixels, and a division by `devicePixelRatio` gives the CSS pixels (see [[positionScale]]). The canvas shows the drop
  * target only while the pointer is over it, and it takes only a drop over it.
  *
  * Text or a link that a person drags from a different app does not arrive: Tauri gives only paths.
  */
object DesktopDrop:

  private val Enter = "tauri://drag-enter"
  private val Over  = "tauri://drag-over"
  private val Drop  = "tauri://drag-drop"
  private val Leave = "tauri://drag-leave"

  private type Listen = js.Function2[String, js.Function1[js.Dynamic, Unit], js.Promise[js.Any]]

  /** `window.__TAURI__.event.listen`, present only inside the desktop shell (`withGlobalTauri`). */
  private def listener: Option[Listen] =
    def child(parent: js.Dynamic, name: String): Option[js.Dynamic] =
      if js.isUndefined(parent) || parent == null then None
      else
        val value = parent.selectDynamic(name)
        if js.isUndefined(value) || value == null then None else Some(value)
    for
      tauri  <- child(js.Dynamic.global.window, "__TAURI__")
      event  <- child(tauri, "event")
      listen <- child(event, "listen") if js.typeOf(listen) == "function"
    yield listen.asInstanceOf[Listen]

  def available: Boolean = listener.isDefined

  /** The pixels of a drag position for one CSS pixel: `devicePixelRatio` on Windows, and 1 on macOS and Linux. */
  def positionScale: Double =
    if dom.window.navigator.userAgent.contains("Windows") then math.max(1e-6, dom.window.devicePixelRatio) else 1.0

  /** The point of `position`, in the CSS pixels of the page. */
  private def pointOf(payload: js.Dynamic): Option[(Double, Double)] =
    if js.isUndefined(payload) || payload == null then None
    else
      val position = payload.selectDynamic("position")
      if js.isUndefined(position) || position == null then None
      else
        val scale = positionScale
        val x     = position.selectDynamic("x")
        val y     = position.selectDynamic("y")
        Option.when(js.typeOf(x) == "number" && js.typeOf(y) == "number"):
          (x.asInstanceOf[Double] / scale, y.asInstanceOf[Double] / scale)

  private def pathsOf(payload: js.Dynamic): Seq[String] =
    if js.isUndefined(payload) || payload == null then Nil
    else
      val paths = payload.selectDynamic("paths")
      if !js.Array.isArray(paths) then Nil
      else paths.asInstanceOf[js.Array[js.Any]].toSeq.collect { case p if js.typeOf(p) == "string" => p.asInstanceOf[String] }

  /** True when the point of `payload` is over `target`. */
  private def isOver(target: dom.Element, payload: js.Dynamic): Boolean =
    pointOf(payload).exists: (x, y) =>
      val r = target.getBoundingClientRect()
      x >= r.left && x < r.right && y >= r.top && y < r.bottom

  /** Follow the drags of files over `target`. `over` gets true while a drag is over `target`. `onDrop` gets the paths
    * of a drop over `target`, and the drop point in the client pixels of the page. Call the result to stop.
    */
  def install(target: dom.Element, over: Boolean => Unit, onDrop: (Seq[String], Option[ClientPoint]) => Unit): () => Unit =
    listener match
      case None => () => ()
      case Some(listen) =>
        var stopped   = false
        var unlistens = List.empty[js.Function0[Unit]]
        def on(event: String)(handler: js.Dynamic => Unit): Unit =
          val callback: js.Function1[js.Dynamic, Unit] = event => if !stopped then handler(event.selectDynamic("payload"))
          listen(event, callback).`then`[Unit]: unlisten =>
            val stop = unlisten.asInstanceOf[js.Function0[Unit]]
            // The view can unmount before Tauri gives the call that stops the listener.
            if stopped then stop() else unlistens = stop :: unlistens
          ()
        on(Enter)(payload => over(isOver(target, payload)))
        on(Over)(payload => over(isOver(target, payload)))
        on(Leave)(_ => over(false))
        on(Drop): payload =>
          over(false)
          val paths = pathsOf(payload)
          if paths.nonEmpty && isOver(target, payload) then onDrop(paths, pointOf(payload).map(ClientPoint(_, _)))
        () =>
          stopped = true
          unlistens.foreach(_())
          unlistens = Nil
          over(false)
