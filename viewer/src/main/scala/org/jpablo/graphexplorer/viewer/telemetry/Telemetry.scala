package org.jpablo.graphexplorer.viewer.telemetry

import org.scalajs.dom

import scala.scalajs.js

object Telemetry:
  private val Prefix = "[telemetry]"
  private val EnabledKey = s"$Prefix.enabled"

  private inline def hasWindow: Boolean =
    js.typeOf(js.Dynamic.global.selectDynamic("window")) != "undefined"

  lazy val enabled: Boolean =
    if !hasWindow then false
    else
      val fromSession =
        Option(dom.window.sessionStorage.getItem(EnabledKey))
          .exists(_ == "1")

      val params = new dom.URLSearchParams(dom.window.location.search)
      val fromQuery =
        Option(params.get("telemetry"))
          .exists(v => v == "1" || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on"))

      if fromQuery then dom.window.sessionStorage.setItem(EnabledKey, "1")
      fromSession || fromQuery

  def nowMs(): Double =
    if hasWindow && js.typeOf(dom.window.performance) != "undefined" then dom.window.performance.now()
    else new js.Date().getTime()

  private def toJsAny(a: Any): js.Any =
    a.asInstanceOf[js.Any]

  private def asJson(fields: Seq[(String, Any)]): String =
    js.JSON.stringify(
      js.Dictionary(fields.map((k, v) => k -> toJsAny(v))*)
    )

  def log(event: String, fields: (String, Any)*): Unit =
    if enabled then
      dom.console.log(
        Prefix,
        asJson(Seq("event" -> event, "tMs" -> nowMs()) ++ fields)
      )

  def markNavigationStart(path: String): Unit =
    if enabled then
      dom.window.sessionStorage.setItem(s"$Prefix.navStart:$path", nowMs().toString)
      log("nav.start", "path" -> path)

  def consumeNavigationStartMs(path: String): Option[Double] =
    if !enabled then None
    else
      val key = s"$Prefix.navStart:$path"
      Option(dom.window.sessionStorage.getItem(key))
        .flatMap(_.toDoubleOption)
        .map: startedAt =>
          dom.window.sessionStorage.removeItem(key)
          nowMs() - startedAt

  inline def time[A](event: String, fields: (String, Any)*)(body: => A): A =
    if !enabled then body
    else
      val startedAt = nowMs()
      val a         = body
      log(event, (fields.toSeq :+ ("dtMs" -> (nowMs() - startedAt)))*)
      a
